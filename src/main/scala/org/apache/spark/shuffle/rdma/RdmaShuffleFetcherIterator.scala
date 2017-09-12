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
import java.util.concurrent.LinkedBlockingQueue
import javax.annotation.concurrent.GuardedBy

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.{Await, TimeoutException}
import scala.concurrent.duration._
import scala.language.postfixOps

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

  private[this] def startAsyncFetches() {
    val rdmaShuffleReaderStats = rdmaShuffleManager.rdmaShuffleReaderStats
    val startRemotePartitionLocationFetch = System.currentTimeMillis()
    // TODO: Aggregate await into a single future
    val groupedRemoteRdmaPartitionLocations =
      (for (partitionId <- startPartition until endPartition) yield {
        try {
          val future = rdmaShuffleManager.fetchRemotePartitionLocations(shuffleId, partitionId)
          Await.result(future, 30 seconds) // TODO: configurable
        } catch {
          case te: TimeoutException =>
            resultsQueue.put(FailureMetadataFetchResult(
              new MetadataFetchFailedException(
                shuffleId,
                partitionId,
                "Timed-out while fetching remote partition locations for ShuffleId: " + shuffleId +
                  " PartitionId: " + partitionId + " from driver")))
            null
          case e: Exception =>
            resultsQueue.put(FailureMetadataFetchResult(
              new MetadataFetchFailedException(
                shuffleId,
                partitionId,
                "Failed to fetch remote partition locations for ShuffleId: " + shuffleId +
                  " PartitionId: " + partitionId + " from driver: " + e)))
            null
        }
      }).filter(_ != null).flatten.filter(_.hostPort != localHostPort).groupBy(_.hostPort)
    logInfo("Fetching remote partition locations took " +
      (System.currentTimeMillis() - startRemotePartitionLocationFetch) + "ms")

    for ((hostPort, partitions) <- groupedRemoteRdmaPartitionLocations) {
      val aggregatedPartitionGroups = new ListBuffer[AggregatedPartitionGroup]

      var curAggregatedPartitionGroup = AggregatedPartitionGroup(0,
        new ListBuffer[RdmaBlockLocation])

      for (blockLocation <- partitions.map(_.rdmaBlockLocation)) {
        if (curAggregatedPartitionGroup.totalLength + blockLocation.length <=
            rdmaShuffleConf.shuffleReadBlockSize) {
          curAggregatedPartitionGroup.locations += blockLocation
          curAggregatedPartitionGroup.totalLength += blockLocation.length
        } else {
          if (curAggregatedPartitionGroup.totalLength > 0) {
            aggregatedPartitionGroups += curAggregatedPartitionGroup
          }
          curAggregatedPartitionGroup = AggregatedPartitionGroup(blockLocation.length,
            new ListBuffer[RdmaBlockLocation])
          curAggregatedPartitionGroup.locations += blockLocation
        }
      }

      if (curAggregatedPartitionGroup.totalLength > 0) {
        aggregatedPartitionGroups += curAggregatedPartitionGroup
      }

      for (aggregatedPartitionGroup <- aggregatedPartitionGroups) {
        // We assume no concurrent use of this variable. In case startAsyncFetches() will be
        // executed asynchronously, then this variable must be handled differently
        numBlocksToFetch += 1

        // TODO: Avoid the thread with something more lightweight
        val fetchThread = new Thread("RdmaShuffleFetcherIterator thread") {
          override def run() {
            val startRemoteFetchTime = System.currentTimeMillis()

            val rdmaChannel = try {
              rdmaShuffleManager.getRdmaChannel(hostPort.host, hostPort.port)
            } catch {
              case e: Exception =>
                logError("Failed to establish a connection to executor: " + hostPort +
                  ", failing pending block fetches. " + e)
                resultsQueue.put(FailureFetchResult(startPartition, hostPort, e))
                return
            }

            val buf = try {
              // Allocate a buffer for the incoming data while connection is established/retrieved
              rdmaShuffleManager.getRdmaByteBufferManagedBuffer(
                aggregatedPartitionGroup.totalLength)
            } catch {
              case e: Exception =>
                logError("Failed to allocate memory for incoming block fetches, failing pending" +
                  " block fetches. " + e)
                resultsQueue.put(FailureFetchResult(startPartition, hostPort, e))
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
                    resultsQueue.put(SuccessFetchResult(startPartition, hostPort, inputStream))
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
                resultsQueue.put(FailureFetchResult(startPartition, hostPort, e))
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

        if (curBytesInFlight < maxBytesInFlight) {
          fetchThread.start()
          curBytesInFlight += aggregatedPartitionGroup.totalLength
        } else {
          fetchesQueue += PendingFetch(fetchThread, aggregatedPartitionGroup)
        }
      }
    }

    if (groupedRemoteRdmaPartitionLocations.isEmpty) {
      numBlocksToFetch += 1

      // If fetch did not yield any buffers, we must trigger next() to continue, as it may be
      // blocked by resultsQueue.take()
      RdmaShuffleFetcherIterator.this.synchronized {
        if (!isStopped) {
          resultsQueue.put(SuccessFetchResult(
            startPartition, localHostPort, new InputStream {
              override def read(): Int = -1
            }))
        }
      }
    }

    isFetchPartitionLocationsInProgress = false
  }

  private[this] def initialize(): Unit = {
    // Add a task completion callback (called in both success case and failure case) to cleanup.
    context.addTaskCompletionListener(_ => cleanup())

    new Thread("RdmaShuffleFetcherIterator startAsyncFetches thread") {
      override def run() { startAsyncFetches() }
    }.start()

    for (partitionId <- startPartition until endPartition) {
      rdmaShuffleManager.shuffleBlockResolver.getLocalRdmaPartition(shuffleId, partitionId).foreach{
        case in: Any =>
          shuffleMetrics.incLocalBlocksFetched(1)
          shuffleMetrics.incLocalBytesRead(in.available())
          resultsQueue.put(SuccessFetchResult(partitionId, localHostPort, in))
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
