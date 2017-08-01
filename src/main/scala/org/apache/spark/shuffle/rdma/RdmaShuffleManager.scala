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

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap

import com.ibm.disni.rdma.verbs.IbvPd
import org.slf4j.LoggerFactory
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.collection.JavaConverters._
import scala.concurrent.{Future, Promise}
import scala.concurrent.ExecutionContext.Implicits.global

import org.apache.spark._
import org.apache.spark.internal.Logging
import org.apache.spark.shuffle.{BaseShuffleHandle, _}
import org.apache.spark.shuffle.rdma.writer.chunkedpartitionagg.RdmaChunkedPartitionAggShuffleWriter
import org.apache.spark.shuffle.rdma.writer.wrapper.RdmaWrapperShuffleWriter
import org.apache.spark.shuffle.sort.{SerializedShuffleHandle, SortShuffleManager}

private[spark] class RdmaShuffleManager(val conf: SparkConf, isDriver: Boolean)
    extends ShuffleManager with Logging {
  private val logger = LoggerFactory.getLogger(classOf[RdmaShuffleManager])
  val rdmaShuffleConf = new RdmaShuffleConf(conf)
  override val shuffleBlockResolver = new RdmaShuffleBlockResolver(this)
  private val executorMap = new ConcurrentHashMap[HostPort, RdmaChannel]()

  private var localHostname: Option[String] = None
  private var localHostnameInUtf: Array[Byte] = _
  private var localPort: Option[Int] = None
  private var rdmaNode: Option[RdmaNode] = None

  // TODO: we can keep in raw form in the driver
  // TODO: naming is too confusing for this type
  case class PartitionLocation(locations: ArrayBuffer[RdmaPartitionLocation],
    promise: Option[Promise[Seq[RdmaPartitionLocation]]])
  private val partitionLocationsMap =
    new ConcurrentHashMap[Int, ConcurrentHashMap[Int, PartitionLocation]]

  val receiveListener = new RdmaCompletionListener {
    override def onSuccess(buf: ByteBuffer): Unit = {
      RdmaRpcMsg(buf) match {
        case publishMsg: RdmaPublishPartitionLocationsRpcMsg =>
          for (r <- publishMsg.rdmaPartitionLocations) {
            partitionLocationsMap.get(publishMsg.shuffleId).get(r.partitionId).locations.
              synchronized {
                if (rdmaShuffleConf.shuffleWriterMethod ==
                  ShuffleWriterMethod.ChunkedPartitionAgg) {
                  // TODO: very ugly, need to improve with a set or something
                  var isExist = false
                  partitionLocationsMap.get(publishMsg.shuffleId).get(r.partitionId).locations.
                    foreach {
                      x: RdmaPartitionLocation =>
                        if (x.hostPort == r.hostPort &&
                          x.rdmaBlockLocation.address == r.rdmaBlockLocation.address) {
                          if (x.rdmaBlockLocation.length < r.rdmaBlockLocation.length) {
                            x.rdmaBlockLocation = r.rdmaBlockLocation
                          }
                          isExist = true
                        }
                    }
                    if (!isExist) {
                      partitionLocationsMap.get(publishMsg.shuffleId).get(r.partitionId).
                        locations += r
                    }
                } else {
                  partitionLocationsMap.get(publishMsg.shuffleId).get(r.partitionId).locations += r
                }
              }
          }

          if (!isDriver && publishMsg.isLast) {
            // Kick-off promise for executors
            assume(publishMsg.rdmaPartitionLocations.last != null)
            val partitionLocation = partitionLocationsMap.get(
              publishMsg.shuffleId).get(publishMsg.rdmaPartitionLocations.last.partitionId)
            partitionLocation.promise match {
              case promise: Some[Promise[Seq[RdmaPartitionLocation]]] =>
                promise.get.trySuccess(partitionLocation.locations)
              case _ =>
            }
          }

        case fetchMsg: RdmaFetchPartitionLocationsRpcMsg =>
          assume(isDriver)
          // TODO: catch null exception if doesn't exist, spawn a future?
          publishPartitionLocations(fetchMsg.host, fetchMsg.port, fetchMsg.shuffleId,
            partitionLocationsMap.get(fetchMsg.shuffleId).get(fetchMsg.partitionId).locations)

        case helloMsg: RdmaExecutorHelloRpcMsg =>
          assume(isDriver)
          val hostPort = new HostPort(helloMsg.host, helloMsg.port)

          if (executorMap.get(hostPort) == null) {
            val f = Future { getRdmaChannel(hostPort.host, hostPort.port) }
            f onSuccess {
              case rdmaChannel =>
                executorMap.put(hostPort, rdmaChannel)
                val buffers = new RdmaAnnounceExecutorsRpcMsg(
                  executorMap.keys.asScala.to[mutable.ArrayBuffer]).toRdmaByteBufferManagedBuffers(
                  getRdmaByteBufferManagedBuffer, rdmaShuffleConf.recvWrSize)

                for (r <- executorMap.values.asScala) {
                  buffers.foreach(_.retain())
                  r.rdmaSendInQueue(
                    new RdmaCompletionListener {
                      override def onSuccess(buf: ByteBuffer): Unit = buffers.foreach(_.release())
                      override def onFailure(e: Throwable): Unit = throw e },
                    buffers.map(_.getAddress),
                    buffers.map(_.getLkey),
                    buffers.map(_.getLength.toInt))
                }
                // Release the refcount take by the allocation
                buffers.foreach(_.release())
            }
          }

        case announceMsg: RdmaAnnounceExecutorsRpcMsg =>
          assume(!isDriver)
          for (hostPort <- announceMsg.executorList) {
            if (hostPort.host != localHostname.get && hostPort.port != localPort.get) {
              Future { getRdmaChannel(hostPort.host, hostPort.port) }
            }
          }

        case _ => logger.warn("RdmaCompletionListener for receive encountered an unidentified RPC")
      }
    }

    override def onFailure(e: Throwable): Unit = throw e
  }

  // TODO: move this code to the beginning of the class
  if (isDriver) {
    rdmaNode = Some(new RdmaNode(conf.get("spark.driver.host"), false, rdmaShuffleConf,
      receiveListener))
    localHostname = Some(getLocalAddress.getHostString)
    localHostnameInUtf = localHostname.get.getBytes(Charset.forName("UTF-8"))
    localPort = Some(getLocalAddress.getPort)
    rdmaShuffleConf.setDriverPort(localPort.get.toString)
  }

  // Called on the driver only!
  override def registerShuffle[K, V, C](
      shuffleId: Int,
      numMaps: Int,
      dependency: ShuffleDependency[K, V, C]): ShuffleHandle = {
    val partitionHashMap = new ConcurrentHashMap[Int, PartitionLocation](
      dependency.partitioner.numPartitions)
    for (partId <- 0 until dependency.partitioner.numPartitions) {
      partitionHashMap.put(partId, PartitionLocation(new ArrayBuffer[RdmaPartitionLocation], None))
    }
    partitionLocationsMap.put(shuffleId, partitionHashMap)

    // TODO: BypassMergeSortShuffleWriter is not supported since it is package private
    if (SortShuffleManager.canUseSerializedShuffle(dependency)) {
      // Otherwise, try to buffer map outputs in a serialized form, since this is more efficient:
      new SerializedShuffleHandle[K, V](
        shuffleId, numMaps, dependency.asInstanceOf[ShuffleDependency[K, V, V]])
    } else {
      // Otherwise, buffer map outputs in a deserialized form:
      new BaseShuffleHandle(shuffleId, numMaps, dependency)
    }
  }

  private def startRdmaNodeIfMissing(): Unit = {
    assume(!isDriver)
    synchronized {
      if (localHostname.isEmpty || localPort.isEmpty) {
        require(rdmaNode.isEmpty)
        rdmaNode = Some(new RdmaNode(SparkEnv.get.blockManager.blockManagerId.host, !isDriver,
          rdmaShuffleConf, receiveListener))
        localHostname = Some(getLocalAddress.getHostString)
        localHostnameInUtf = localHostname.get.getBytes(Charset.forName("UTF-8"))
        localPort = Some(getLocalAddress.getPort)
      }
    }

    require(rdmaNode.isDefined)
    // Establish a connection to the driver in the background
    val f = Future { getRdmaChannel(rdmaShuffleConf.driverHost, rdmaShuffleConf.driverPort) }
    f onSuccess {
      case rdmaChannel =>
        val buffers = new RdmaExecutorHelloRpcMsg(localHostname.get, localPort.get).
          toRdmaByteBufferManagedBuffers(getRdmaByteBufferManagedBuffer,
          rdmaShuffleConf.recvWrSize)

        rdmaChannel.rdmaSendInQueue(
          new RdmaCompletionListener {
            override def onSuccess(buf: ByteBuffer): Unit = buffers.foreach(_.release())
            override def onFailure(e: Throwable): Unit = throw e },
          buffers.map(_.getAddress),
          buffers.map(_.getLkey),
          buffers.map(_.getLength.toInt))
    }
  }

  override def getReader[K, C](handle: ShuffleHandle, startPartition: Int, endPartition: Int,
      context: TaskContext): ShuffleReader[K, C] = {
    // RdmaNode can't be initialized in the c'tor for executors, so the first call will initialize
    startRdmaNodeIfMissing()

    val baseShuffleHandle = handle.asInstanceOf[BaseShuffleHandle[K, _, C]]
    // registerShuffle() is only called on the driver, so we let the first caller of getReader() to
    // initialize the structures for a new ShuffleId, in case getWriter wasn't called earlier
    partitionLocationsMap.putIfAbsent(baseShuffleHandle.shuffleId,
      new ConcurrentHashMap[Int, PartitionLocation]())

    new RdmaShuffleReader(baseShuffleHandle, startPartition, endPartition, context)
  }

  override def getWriter[K, V](handle: ShuffleHandle, mapId: Int, context: TaskContext)
      : ShuffleWriter[K, V] = {
    // RdmaNode can't be initialized in the c'tor for executors, so the first call will initialize
    startRdmaNodeIfMissing()

    val baseShuffleHandle = handle.asInstanceOf[BaseShuffleHandle[K, V, _]]
    // registerShuffle() is only called on the driver, so we let the first caller of getWriter() to
    // initialize the structures for a new ShuffleId
    shuffleBlockResolver.newShuffleWriter(baseShuffleHandle)
    partitionLocationsMap.putIfAbsent(baseShuffleHandle.shuffleId,
      new ConcurrentHashMap[Int, PartitionLocation]())

    rdmaShuffleConf.shuffleWriterMethod match {
      case ShuffleWriterMethod.Wrapper =>
        new RdmaWrapperShuffleWriter(shuffleBlockResolver, baseShuffleHandle, mapId, context)
      case ShuffleWriterMethod.ChunkedPartitionAgg =>
        new RdmaChunkedPartitionAggShuffleWriter(shuffleBlockResolver, baseShuffleHandle,
          mapId, context)
    }
  }

  override def unregisterShuffle(shuffleId: Int): Boolean = {
    shuffleBlockResolver.removeShuffle(shuffleId)
    partitionLocationsMap.remove(shuffleId)
    true
  }

  override def stop(): Unit = {
    shuffleBlockResolver.stop()
    rdmaNode match {
      case Some(x) => x.stop()
      case _ =>
    }
  }

  def publishPartitionLocations(host : String, port : Int, shuffleId: Int,
      rdmaPartitionLocations: ArrayBuffer[RdmaPartitionLocation]) {
    // TODO: we can avoid blocking by defining a future with onsuccess that will perform the send
    val rdmaChannel = getRdmaChannel(host, port)

    val buffers = new RdmaPublishPartitionLocationsRpcMsg(shuffleId,
      rdmaPartitionLocations).toRdmaByteBufferManagedBuffers(getRdmaByteBufferManagedBuffer,
      rdmaShuffleConf.recvWrSize)

    rdmaChannel.rdmaSendInQueue(
      new RdmaCompletionListener {
        override def onSuccess(buf: ByteBuffer): Unit = buffers.foreach(_.release())
        override def onFailure(e: Throwable): Unit = throw e },
      buffers.map(_.getAddress),
      buffers.map(_.getLkey),
      buffers.map(_.getLength.toInt))
  }

  def fetchRemotePartitionLocations(shuffleId: Int, partitionId : Int)
      : Future[Seq[RdmaPartitionLocation]] = {
    assume(!isDriver)
    // TODO: we can avoid blocking by defining a future with onsuccess that will perform the send
    val rdmaChannel = getRdmaChannel(rdmaShuffleConf.driverHost, rdmaShuffleConf.driverPort)

    val fetchRemotePartitionLocationPromise: Promise[Seq[RdmaPartitionLocation]] = Promise()
    // We assume that only one consumer mutates partitionLocationsMap for this particular
    // (shuffleId, partitionId)
    require (partitionLocationsMap.get(shuffleId) != null)
    partitionLocationsMap.get(shuffleId).put(partitionId, PartitionLocation(
      new ArrayBuffer[RdmaPartitionLocation], Some(fetchRemotePartitionLocationPromise)))

    // TODO: optimize with Utf
    val buffers = new RdmaFetchPartitionLocationsRpcMsg(localHostname.get, localPort.get, shuffleId,
      partitionId).toRdmaByteBufferManagedBuffers(getRdmaByteBufferManagedBuffer,
      rdmaShuffleConf.recvWrSize)

    rdmaChannel.rdmaSendInQueue(
      new RdmaCompletionListener {
        override def onSuccess(buf: ByteBuffer): Unit = buffers.foreach(_.release())
        override def onFailure(e: Throwable): Unit = throw e },
      buffers.map(_.getAddress),
      buffers.map(_.getLkey),
      buffers.map(_.getLength.toInt))

    fetchRemotePartitionLocationPromise.future
  }

  def getRdmaChannel(host: String, port: Int): RdmaChannel =
    rdmaNode.get.getRdmaChannel(new InetSocketAddress(host, port))

  // TODO: can we drop the managed buffers?
  def getRdmaByteBufferManagedBuffer(length : Int): RdmaByteBufferManagedBuffer = {
    new RdmaByteBufferManagedBuffer(new RdmaRegisteredBuffer(rdmaNode.get.getRdmaBufferManager,
      length, false), length)
  }

  // TODO: can we clean this disni dependency out?
  def getPd: IbvPd = rdmaNode.get.getRdmaBufferManager.getPd

  def getLocalAddress: InetSocketAddress = rdmaNode.get.getLocalAddress
}
