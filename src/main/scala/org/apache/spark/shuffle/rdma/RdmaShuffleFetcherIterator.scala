/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.shuffle.rdma

import java.io.InputStream
import java.nio.ByteBuffer
import java.util.Timer
import java.util.concurrent.LinkedBlockingQueue
import javax.annotation.concurrent.GuardedBy

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent._
import scala.concurrent.ExecutionContext.Implicits.global

import org.apache.spark.{SparkEnv, TaskContext}
import org.apache.spark.internal.Logging
import org.apache.spark.network.buffer.ManagedBuffer
import org.apache.spark.shuffle.{FetchFailedException, MetadataFetchFailedException}
import org.apache.spark.shuffle.rdma.RdmaShuffleFetcherIterator.{FailureFetchResult, FailureMetadataFetchResult, FetchResult, SuccessFetchResult}
import org.apache.spark.storage.BlockManagerId
import org.apache.spark.util.Utils

private[spark] final class RdmaShuffleFetcherIterator(
    context: TaskContext,
    startPartition: Int,
    endPartition: Int,
    shuffleId : Int)
  extends Iterator[InputStream] with Logging {
  private[this] val startTime = System.currentTimeMillis

  @volatile private[this] var numBlocksToFetch = 0
  @volatile private[this] var isFetchPartitionLocationsInProgress = true
  @volatile private[this] var numBlocksProcessed = 0

  private[this] val rdmaShuffleManager =
    SparkEnv.get.shuffleManager.asInstanceOf[RdmaShuffleManager]

  private[this] val resultsQueue = new LinkedBlockingQueue[FetchResult]

  @volatile private[this] var currentResult: FetchResult = _

  private[this] val shuffleMetrics = context.taskMetrics().createTempShuffleReadMetrics()

  @GuardedBy("this")
  private[this] var isStopped = false

  private[this] val localHostPort = rdmaShuffleManager.getLocalHostPort
  private[this] val rdmaShuffleConf = rdmaShuffleManager.rdmaShuffleConf

  private[this] val maxBytesInFlight = rdmaShuffleConf.maxBytesInFlight
  private[this] var curBytesInFlight = 0L

  case class AggregatedPartitionGroup(var totalLength: Int,
    locations: ListBuffer[RdmaBlockLocation])
  case class PendingFetch(fetchThread: Thread, aggregatedPartitionGroup: AggregatedPartitionGroup)
  private[this] val fetchesQueue = new mutable.Queue[PendingFetch]()

  private[this] val rdmaShuffleReaderStats = rdmaShuffleManager.rdmaShuffleReaderStats

  initialize()

  private[this] def cleanup() {
    synchronized { isStopped = true }

    currentResult match {
      case SuccessFetchResult(_, _, inputStream) => inputStream.close()
      case _ =>
    }
    currentResult = null

    val iter = resultsQueue.iterator()
    while (iter.hasNext) {
      val result = iter.next()
      result match {
        case SuccessFetchResult(_, hostPort, inputStream) =>
          if (hostPort != localHostPort) {
            shuffleMetrics.incRemoteBytesRead(inputStream.available)
            shuffleMetrics.incRemoteBlocksFetched(1)
          }
          inputStream.close()
        case _ =>
      }
    }
  }

  private[this] def wrapFuturesWithTimeout[T](futureList: Seq[Future[T]], timeoutMs: Int)
      (implicit ec: ExecutionContext): Future[Seq[T]] = {
    val promise = Promise[Seq[T]]()
    val timerTask = new java.util.TimerTask {
      override def run(): Unit = promise.failure(new TimeoutException())
    }

    val timer = new Timer(true)
    timer.schedule(timerTask, timeoutMs)

    val combinedFuture = Future.sequence(futureList)
    combinedFuture.onComplete( _ => timerTask.cancel() )

    Future.firstCompletedOf(List(combinedFuture, promise.future))
  }

  private[this] def insertDummyResult(): Unit = {
    RdmaShuffleFetcherIterator.this.synchronized {
      if (!isStopped) {
        RdmaShuffleFetcherIterator.this.synchronized {
          resultsQueue.put(SuccessFetchResult(
            startPartition, localHostPort, new InputStream {
              override def read(): Int = -1
            }))
        }
      }
    }
  }

  private[this] def createRdmaBlockFetchThread(hostPort: HostPort,
      aggregatedPartitionGroup: AggregatedPartitionGroup): Thread = {
    new Thread("RdmaShuffleFetcherIterator thread") {
      override def run() {
        val startRemoteFetchTime = System.currentTimeMillis()

        val rdmaChannel = try {
          rdmaShuffleManager.getRdmaChannel(hostPort.host, hostPort.port)
        } catch {
          case e: Exception =>
            logError("Failed to establish a connection to executor: " + hostPort +
              ", failing pending block fetches. " + e)
            RdmaShuffleFetcherIterator.this.synchronized {
              resultsQueue.put(FailureFetchResult(startPartition, hostPort, e))
            }
            return
        }

        val buf = try {
          // Allocate a buffer for the incoming data while connection is established/retrieved
          rdmaShuffleManager.getRdmaByteBufferManagedBuffer(aggregatedPartitionGroup.totalLength)
        } catch {
          case e: Exception =>
            logError("Failed to allocate memory for incoming block fetches, failing pending" +
              " block fetches. " + e)
            RdmaShuffleFetcherIterator.this.synchronized {
              resultsQueue.put(FailureFetchResult(startPartition, hostPort, e))
            }
            return
        }

        val listener = new RdmaCompletionListener {
          override def onSuccess(paramBuf: ByteBuffer): Unit = {
            // Only add the buffer to results queue if the iterator is not zombie,
            // i.e. cleanup() has not been called yet.
            RdmaShuffleFetcherIterator.this.synchronized {
              if (!isStopped) {
                val inputStream = new BufferReleasingInputStream(buf.createInputStream(), buf)
                // TODO: startPartition may only be one of the partitions to report
                RdmaShuffleFetcherIterator.this.synchronized {
                  resultsQueue.put(SuccessFetchResult(startPartition, hostPort, inputStream))
                }
                if (rdmaShuffleReaderStats != null) {
                  rdmaShuffleReaderStats.updateRemoteFetchHistogram(hostPort,
                    (System.currentTimeMillis() - startRemoteFetchTime).toInt)
                }
              } else {
                buf.release()
              }
            }
            // TODO: startPartition may only be one of the partitions to report
            logTrace("Got remote block " + startPartition + " from " + hostPort.host + ":" +
              hostPort.port + " after " + Utils.getUsedTimeMs(startTime))
          }

          override def onFailure(e: Throwable): Unit = {
            logError("Failed to get block(s) from: " + hostPort + ", Exception: " + e)
            // TODO: startPartition may only be one of the partitions to report
            RdmaShuffleFetcherIterator.this.synchronized {
              resultsQueue.put(FailureFetchResult(startPartition, hostPort, e))
            }
            buf.release()
            // We skip curBytesInFlight since we expect one failure to fail the whole task
          }
        }

        try {
          rdmaChannel.rdmaReadInQueue(
            listener,
            buf.getAddress,
            buf.getLkey,
            aggregatedPartitionGroup.locations.map(_.length).toArray,
            aggregatedPartitionGroup.locations.map(_.address).toArray,
            aggregatedPartitionGroup.locations.map(_.mKey).toArray)
        } catch {
          case e: Exception => listener.onFailure(e)
        }
      }
    }
  }

  private[this] def startAsyncRemoteFetches(): Unit = {
    val startRemotePartitionLocationFetch = System.currentTimeMillis()

    val futureSeq = for (partitionId <- startPartition until endPartition) yield {
      try {
        rdmaShuffleManager.fetchRemotePartitionLocations(shuffleId, partitionId)
      } catch {
        case e: Exception =>
          RdmaShuffleFetcherIterator.this.synchronized {
            resultsQueue.put(FailureMetadataFetchResult(
              new MetadataFetchFailedException(
                shuffleId,
                partitionId,
                "Failed to fetch remote partition locations for ShuffleId: " + shuffleId +
                  " PartitionId: " + partitionId + " from driver: " + e)))
          }
          numBlocksToFetch += 1
          null
      }
    }

    if (futureSeq.contains(null)) {
      // Abort everything (error will be waiting in resultsQueue)
      isFetchPartitionLocationsInProgress = false
    } else {
      val timeoutFutureSeq = wrapFuturesWithTimeout(futureSeq,
        rdmaShuffleConf.partitionLocationFetchTimeout)

      timeoutFutureSeq.onSuccess { case remotePartitionLocations =>
        logInfo("Fetching remote partition locations took " +
          (System.currentTimeMillis() - startRemotePartitionLocationFetch) + "ms")

        val groupedRemoteRdmaPartitionLocations = remotePartitionLocations.filter(_ != null)
          .flatten.filter(_.hostPort != localHostPort).groupBy(_.hostPort)

        for ((hostPort, partitions) <- groupedRemoteRdmaPartitionLocations) {
          val aggregatedPartitionGroups = new ListBuffer[AggregatedPartitionGroup]

          var curAggregatedPartitionGroup = AggregatedPartitionGroup(0,
            new ListBuffer[RdmaBlockLocation])

          for (blockLocation <- partitions.map(_.rdmaBlockLocation)) {
            if (curAggregatedPartitionGroup.totalLength + blockLocation.length <=
                rdmaShuffleConf.shuffleReadBlockSize) {
              curAggregatedPartitionGroup.totalLength += blockLocation.length
            } else {
              if (curAggregatedPartitionGroup.totalLength > 0) {
                aggregatedPartitionGroups += curAggregatedPartitionGroup
              }
              curAggregatedPartitionGroup = AggregatedPartitionGroup(blockLocation.length,
                new ListBuffer[RdmaBlockLocation])
            }
            curAggregatedPartitionGroup.locations += blockLocation
          }

          if (curAggregatedPartitionGroup.totalLength > 0) {
            aggregatedPartitionGroups += curAggregatedPartitionGroup
          }

          for (aggregatedPartitionGroup <- aggregatedPartitionGroups) {
            numBlocksToFetch += 1

            // TODO: Avoid the thread with something more lightweight
            val fetchThread = createRdmaBlockFetchThread(hostPort, aggregatedPartitionGroup)

            if (curBytesInFlight < maxBytesInFlight) {
              fetchThread.start()
              curBytesInFlight += aggregatedPartitionGroup.totalLength
            } else {
              fetchesQueue += PendingFetch(fetchThread, aggregatedPartitionGroup)
            }
          }
        }

        if (groupedRemoteRdmaPartitionLocations.isEmpty) {
          // If fetch did not yield any buffers, we must trigger next() to continue, as it may be
          // blocked by resultsQueue.take()
          insertDummyResult()
          numBlocksToFetch += 1
        }

        isFetchPartitionLocationsInProgress = false
      }

      timeoutFutureSeq.onFailure { case error =>
        error match {
          case _: TimeoutException =>
            RdmaShuffleFetcherIterator.this.synchronized {
              resultsQueue.put(FailureMetadataFetchResult(
                new MetadataFetchFailedException(
                  shuffleId,
                  startPartition,
                  "Timed-out while fetching remote partition locations for ShuffleId: " +
                    shuffleId + " PartitionIds: " +
                    (startPartition until endPartition).mkString(", ") + " from driver, consider " +
                    "increasing the value of spark.shuffle.rdma.partitionLocationFetchTimeout " +
                    "(current value: " + rdmaShuffleConf.partitionLocationFetchTimeout + ")")))
            }
          case e: Exception =>
            RdmaShuffleFetcherIterator.this.synchronized {
              resultsQueue.put(FailureMetadataFetchResult(
                new MetadataFetchFailedException(
                  shuffleId,
                  startPartition,
                  "Failed to fetch remote partition locations for ShuffleId: " + shuffleId +
                    " PartitionIds: " + (startPartition until endPartition).mkString(", ") +
                    " from driver: " + e)))
            }
          case _ =>
            // If fetch did not yield any buffers, we must trigger next() to continue, as it may be
            // blocked by resultsQueue.take()
            insertDummyResult()
        }

        numBlocksToFetch += 1
        isFetchPartitionLocationsInProgress = false
      }
    }
  }

  private[this] def initialize(): Unit = {
    // Add a task completion callback (called in both success case and failure case) to cleanup.
    context.addTaskCompletionListener(_ => cleanup())

    startAsyncRemoteFetches()

    for (partitionId <- startPartition until endPartition) {
      rdmaShuffleManager.shuffleBlockResolver.getLocalRdmaPartition(shuffleId, partitionId).foreach{
        case in: Any =>
          shuffleMetrics.incLocalBlocksFetched(1)
          shuffleMetrics.incLocalBytesRead(in.available())
          RdmaShuffleFetcherIterator.this.synchronized {
            resultsQueue.put(SuccessFetchResult(partitionId, localHostPort, in))
          }
          numBlocksToFetch += 1
        case _ =>
      }
    }
  }

  override def hasNext: Boolean = {
    numBlocksProcessed < numBlocksToFetch || isFetchPartitionLocationsInProgress
  }

  override def next(): InputStream = {
    numBlocksProcessed += 1

    val startFetchWait = System.currentTimeMillis()
    currentResult = resultsQueue.take()
    val result = currentResult
    val stopFetchWait = System.currentTimeMillis()
    shuffleMetrics.incFetchWaitTime(stopFetchWait - startFetchWait)

    result match {
      case SuccessFetchResult(_, hostPort, inputStream) =>
        if (hostPort != localHostPort) {
          shuffleMetrics.incRemoteBytesRead(inputStream.available)
          shuffleMetrics.incRemoteBlocksFetched(1)
          curBytesInFlight -= inputStream.available
        }
      case _ =>
    }

    // Start some pending remote fetches
    while (fetchesQueue.nonEmpty && curBytesInFlight < maxBytesInFlight) {
      curBytesInFlight += fetchesQueue.front.aggregatedPartitionGroup.totalLength
      fetchesQueue.dequeue().fetchThread.start()
    }

    result match {
      case FailureMetadataFetchResult(e) => throw e

      case FailureFetchResult(partitionId, hostPort, e) =>
        // TODO: BlockManagerId is imprecise due to lack of executorId name, do we need to bookkeep?
        // TODO: Throw exceptions for all of the mapIds?
        throw new FetchFailedException(
          BlockManagerId(hostPort.host, hostPort.host, hostPort.port),
          shuffleId,
          0,
          partitionId,
          e)

      case SuccessFetchResult(_, _, inputStream) =>
        inputStream
    }
  }
}

// TODO: can we avoid this extra stream? just have release on the bytebufferbackedinputstream?
private class BufferReleasingInputStream(
    private val delegate: InputStream,
    private val buf: ManagedBuffer)
  extends InputStream {
  private[this] var closed = false

  override def read(): Int = delegate.read()

  override def close(): Unit = {
    if (!closed) {
      delegate.close()
      buf.release()
      closed = true
    }
  }

  override def available(): Int = delegate.available()

  override def mark(readlimit: Int): Unit = delegate.mark(readlimit)

  override def skip(n: Long): Long = delegate.skip(n)

  override def markSupported(): Boolean = delegate.markSupported()

  override def read(b: Array[Byte]): Int = delegate.read(b)

  override def read(b: Array[Byte], off: Int, len: Int): Int = delegate.read(b, off, len)

  override def reset(): Unit = delegate.reset()
}

private[rdma]
object RdmaShuffleFetcherIterator {

  private[rdma] sealed trait FetchResult { }

  private[rdma] case class SuccessFetchResult(
      partitionId: Int,
      hostPort: HostPort,
      inputStream: InputStream) extends FetchResult {
    require(inputStream != null)
  }

  private[rdma] case class FailureFetchResult(
      partitionId: Int,
      hostPort: HostPort,
      e: Throwable) extends FetchResult

  private[rdma] case class FailureMetadataFetchResult(e: MetadataFetchFailedException)
      extends FetchResult
}
