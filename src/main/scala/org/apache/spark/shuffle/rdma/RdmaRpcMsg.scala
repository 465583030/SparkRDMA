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

import java.io.{DataInputStream, DataOutputStream, EOFException}
import java.nio.ByteBuffer
import java.nio.charset.Charset

import org.slf4j.LoggerFactory
import scala.collection.mutable.ArrayBuffer

import org.apache.spark.internal.Logging
import org.apache.spark.shuffle.rdma.RdmaRpcMsgType.RdmaRpcMsgType

object RdmaRpcMsgType extends Enumeration {
  type RdmaRpcMsgType = Value
  val PublishPartitionLocations, FetchPartitionLocations, ExecutorHello, AnnounceExecutors = Value
}

trait RdmaRpcMsg {
  protected def msgType: RdmaRpcMsgType
  protected def getLengthInSegments(segmentSize: Int): Array[Int]
  protected def read(dataIn: DataInputStream): Unit
  protected def writeSegments(outs: Iterator[(DataOutputStream, Int)]): Unit

  private final val overhead: Int = 4 + 4 // 4 + 4 for msg length and type
  // TODO: split to driver to executor - only has a single partitionIs and ShuffleId, we can also
  // aggregate per executor, we can define aggregators for RdmaPartitionLocation
  // TODO: executor to driver also has only a single partitionId and shuffle id and all of the host
  // and ports are the same

  def toRdmaByteBufferManagedBuffers(allocator: Int => RdmaByteBufferManagedBuffer,
      maxSegmentSize: Int): Array[RdmaByteBufferManagedBuffer] = {
    val arrSegmentLengths = getLengthInSegments(maxSegmentSize - overhead)
    val bufs = Array.fill(arrSegmentLengths.length) { allocator(maxSegmentSize) }

    val outs = for ((buf, bufferIndex) <- bufs.zipWithIndex) yield {
      val out = new DataOutputStream(buf.createOutputStream())
      out.writeInt(overhead + arrSegmentLengths(bufferIndex))
      out.writeInt(msgType.id)
      (out, arrSegmentLengths(bufferIndex))
    }

    writeSegments(outs.iterator)
    outs.foreach(_._1.close())

    bufs
  }
}

object RdmaRpcMsg extends Logging {
  private val logger = LoggerFactory.getLogger(classOf[RdmaRpcMsg])

  def apply(buf: ByteBuffer): RdmaRpcMsg = {
    val in = new DataInputStream(new ByteBufferBackedInputStream(buf))
    val msgLength = in.readInt()
    buf.limit(msgLength)

    RdmaRpcMsgType(in.readInt()) match {
      case RdmaRpcMsgType.PublishPartitionLocations =>
        RdmaPublishPartitionLocationsRpcMsg(in)
      case RdmaRpcMsgType.FetchPartitionLocations =>
        RdmaFetchPartitionLocationsRpcMsg(in)
      case RdmaRpcMsgType.ExecutorHello =>
        RdmaExecutorHelloRpcMsg(in)
      case RdmaRpcMsgType.AnnounceExecutors =>
        RdmaAnnounceExecutorsRpcMsg(in)
      case _ =>
        logger.warn("Received an unidentified RPC")
        null
    }
  }
}

class RdmaPublishPartitionLocationsRpcMsg(
    var shuffleId: Int,
    var rdmaPartitionLocations: ArrayBuffer[RdmaPartitionLocation])
  extends RdmaRpcMsg {
  private final val overhead: Int = 1 + 4 // 1 for isLast bool per segment, + 4 for shuffleId
  var isLast = false

  private def this() = this(0, new ArrayBuffer[RdmaPartitionLocation])  // For deserialization only

  override protected def msgType: RdmaRpcMsgType = RdmaRpcMsgType.PublishPartitionLocations

  override protected def getLengthInSegments(segmentSize: Int): Array[Int] = {
    var segmentSizes = new ArrayBuffer[Int]
    segmentSizes += overhead
    for (rdmaPartitionLocation <- rdmaPartitionLocations) {
      if (segmentSizes.last + rdmaPartitionLocation.serializedLength <= segmentSize) {
        segmentSizes.update(segmentSizes.length - 1, segmentSizes.last +
          rdmaPartitionLocation.serializedLength)
      } else {
        segmentSizes += (overhead + rdmaPartitionLocation.serializedLength)
      }
    }

    segmentSizes.toArray
  }

  override protected def writeSegments(outs: Iterator[(DataOutputStream, Int)]): Unit = {
    var curOut: (DataOutputStream, Int) = null
    var curSegmentLength = 0

    def nextOut() {
      curOut = outs.next()
      curOut._1.writeBoolean(!outs.hasNext)
      curOut._1.writeInt(shuffleId)
      curSegmentLength = overhead
    }

    nextOut()
    for (rdmaPartitionLocation <- rdmaPartitionLocations) {
      if (curSegmentLength + rdmaPartitionLocation.serializedLength > curOut._2) {
        nextOut()
      }
      curSegmentLength += rdmaPartitionLocation.serializedLength
      rdmaPartitionLocation.write(curOut._1)
    }
  }

  override protected def read(in: DataInputStream): Unit = {
    isLast = in.readBoolean()
    shuffleId = in.readInt()
    scala.util.control.Exception.ignoring(classOf[EOFException]) {
      while (true) rdmaPartitionLocations += RdmaPartitionLocation(in)
    }
  }
}

object RdmaPublishPartitionLocationsRpcMsg {
  def apply(in: DataInputStream): RdmaRpcMsg = {
    val obj = new RdmaPublishPartitionLocationsRpcMsg()
    obj.read(in)
    obj
  }
}

class RdmaFetchPartitionLocationsRpcMsg(
    var host: String,
    var port: Int,
    var shuffleId: Int,
    var partitionId: Int)
  extends RdmaRpcMsg {

  private def this() = this(null, 0, 0, 0)  // For deserialization only

  private var hostnameInUtf: Array[Byte] = _

  override protected def msgType: RdmaRpcMsgType = RdmaRpcMsgType.FetchPartitionLocations

  override protected def getLengthInSegments(segmentSize: Int): Array[Int] = {
    if (hostnameInUtf == null) {
      hostnameInUtf = host.getBytes(Charset.forName("UTF-8"))
    }
    val length = 2 + hostnameInUtf.length + 4 + 4 + 4
    require(length <= segmentSize, "RdmaBuffer RPC segment size is too small")

    Array.fill(1) { length }
  }

  override protected def writeSegments(outs: Iterator[(DataOutputStream, Int)]): Unit = {
    val out = outs.next()._1
    if (hostnameInUtf == null) {
      hostnameInUtf = host.getBytes(Charset.forName("UTF-8"))
    }
    out.writeShort(hostnameInUtf.length)
    out.write(hostnameInUtf)
    out.writeInt(port)
    out.writeInt(shuffleId)
    out.writeInt(partitionId)
  }

  override protected def read(in: DataInputStream): Unit = {
    val hostLength = in.readShort()
    hostnameInUtf = new Array[Byte](hostLength)
    in.read(hostnameInUtf, 0, hostLength)
    host = new String(hostnameInUtf, "UTF-8")
    port = in.readInt()
    shuffleId = in.readInt()
    partitionId = in.readInt()
  }
}

object RdmaFetchPartitionLocationsRpcMsg {
  def apply(in: DataInputStream): RdmaRpcMsg = {
    val obj = new RdmaFetchPartitionLocationsRpcMsg()
    obj.read(in)
    obj
  }
}

class RdmaExecutorHelloRpcMsg(
    var host: String,
    var port: Int)
  extends RdmaRpcMsg {

  private def this() = this(null, 0)  // For deserialization only

  private var hostnameInUtf: Array[Byte] = _

  override protected def msgType: RdmaRpcMsgType = RdmaRpcMsgType.ExecutorHello

  override protected def getLengthInSegments(segmentSize: Int): Array[Int] = {
    if (hostnameInUtf == null) {
      hostnameInUtf = host.getBytes(Charset.forName("UTF-8"))
    }
    val length = 2 + hostnameInUtf.length + 4
    require(length <= segmentSize, "RdmaBuffer RPC segment size is too small")

    Array.fill(1) { length }
  }

  override protected def writeSegments(outs: Iterator[(DataOutputStream, Int)]): Unit = {
    val out = outs.next()._1
    if (hostnameInUtf == null) {
      hostnameInUtf = host.getBytes(Charset.forName("UTF-8"))
    }
    out.writeShort(hostnameInUtf.length)
    out.write(hostnameInUtf)
    out.writeInt(port)
  }

  override protected def read(in: DataInputStream): Unit = {
    val hostLength = in.readShort()
    hostnameInUtf = new Array[Byte](hostLength)
    in.read(hostnameInUtf, 0, hostLength)
    host = new String(hostnameInUtf, "UTF-8")
    port = in.readInt()
  }
}

object RdmaExecutorHelloRpcMsg {
  def apply(in: DataInputStream): RdmaRpcMsg = {
    val obj = new RdmaExecutorHelloRpcMsg()
    obj.read(in)
    obj
  }
}

class RdmaAnnounceExecutorsRpcMsg(
    var executorList: ArrayBuffer[HostPort])
  extends RdmaRpcMsg {

  private def this() = this(new ArrayBuffer[HostPort])  // For deserialization only

  override protected def msgType: RdmaRpcMsgType = RdmaRpcMsgType.AnnounceExecutors

  override protected def getLengthInSegments(segmentSize: Int): Array[Int] = {
    var segmentSizes = new ArrayBuffer[Int]

    for (hostPort <- executorList) {
      val hostnameInUtf = hostPort.host.getBytes(Charset.forName("UTF-8"))
      val length = 2 + hostnameInUtf.length + 4

      if (!segmentSizes.isEmpty && (segmentSizes.last + length <= segmentSize)) {
        segmentSizes.update(segmentSizes.length - 1, segmentSizes.last + length)
      } else {
        segmentSizes += length
      }
    }

    segmentSizes.toArray
  }

  override protected def writeSegments(outs: Iterator[(DataOutputStream, Int)]): Unit = {
    var curOut: (DataOutputStream, Int) = null
    var curSegmentLength = 0

    def nextOut() {
      curOut = outs.next()
      curSegmentLength = 0
    }

    nextOut()
    for (hostPort <- executorList) {
      val hostnameInUtf = hostPort.host.getBytes(Charset.forName("UTF-8"))
      val length = 2 + hostnameInUtf.length + 4
      if (curSegmentLength + length > curOut._2) {
        nextOut()
      }
      curSegmentLength += length
      curOut._1.writeShort(hostnameInUtf.length)
      curOut._1.write(hostnameInUtf)
      curOut._1.writeInt(hostPort.port)
    }
  }

  override protected def read(in: DataInputStream): Unit = {
    scala.util.control.Exception.ignoring(classOf[EOFException]) {
      while (true) {
        val hostLength = in.readShort()
        val hostnameInUtf = new Array[Byte](hostLength)
        in.read(hostnameInUtf, 0, hostLength)
        val host = new String(hostnameInUtf, "UTF-8")
        val port = in.readInt()
        executorList += HostPort(host, port)
      }
    }
  }
}

object RdmaAnnounceExecutorsRpcMsg {
  def apply(in: DataInputStream): RdmaRpcMsg = {
    val obj = new RdmaAnnounceExecutorsRpcMsg()
    obj.read(in)
    obj
  }
}
