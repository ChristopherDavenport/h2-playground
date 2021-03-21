package org.http4s.ember.h2

import cats._
import cats.syntax.all._
import scodec.bits._

sealed trait Frame
object Frame {
/*
  All frames begin with a fixed 9-octet header followed by a variable-
  length payload.

    +-----------------------------------------------+
    |                 Length (24)                   |
    +---------------+---------------+---------------+
    |   Type (8)    |   Flags (8)   |
    +-+-------------+---------------+-------------------------------+
    |R|                 Stream Identifier (31)                      |
    +=+=============================================================+
    |                   Frame Payload (0...)                      ...
    +---------------------------------------------------------------+

  Chris:
  To Unpack this each from should consist of 
  Length 3 bytes
  Type 1 Byte
  Flags 1 Byte
  1 bit that must be sent 0x0, and must be ignored
  31 bit unsigned Integer
  FramePayload that consists of Length (bits or bytes???)

*/

  case class RawFrame(
    length: Int, // 3 bytes is within int range
    `type`: Byte,
    flags: Byte,
    identifier: Int, // 31 bit Integer
    payload: ByteVector
  )

  def fromRaw(rawFrame: RawFrame): Option[Frame] = {
    rawFrame.`type` match {
      case Data.`type` => Data.fromRawFrame(rawFrame)
      case _ => ???
    }
  }

  /*
    +---------------+
    |Pad Length? (8)|
    +---------------+-----------------------------------------------+
    |                            Data (*)                         ...
    +---------------------------------------------------------------+
    |                           Padding (*)                       ...
    +---------------------------------------------------------------+
    */
  case class Data(identifier: Int, data: ByteVector, pad: Option[ByteVector], endStream: Boolean) extends Frame
  object Data {
    val `type`: Byte = 0x0
    // 2 flags 
    // EndStream = Bit 0 indicates this is the last frame this will send
    // Padded = Bit 3 indicates
    def fromRawFrame(rawFrame: RawFrame): Option[Data] = {
      if (rawFrame.`type` === `type`) {
        val endStream = (rawFrame.flags & (0x01 << 0)) != 0
        val padded = (rawFrame.flags & (0x01 << 3)) != 0
        if (padded) {
          val padLength = rawFrame.payload(0)
          val baseSize = rawFrame.payload.size.toInt - 1
          val dataSize = baseSize - padLength
          val data = rawFrame.payload.drop(1).take(dataSize)
          val pad = rawFrame.payload.drop(1).drop(dataSize)

          Data(rawFrame.identifier, data, Some(pad), endStream).some
        } else Data(rawFrame.identifier, rawFrame.payload, None, endStream).some
      } else None
    }

    def toRawFrame(data: Data): RawFrame = {
      val payload = data.pad.map(p  => 
        ByteVector(p.length.toByte) ++
        data.data ++
        p
      ).getOrElse(data.data)
      val flags: Byte = {
        var init: Int = 0x0
        val endStreamBitSet: Int = if (data.endStream) (init | (1 << 0)) else init
        val paddedSet: Int = if (data.pad.isDefined) endStreamBitSet | (1 << 3) else endStreamBitSet
        paddedSet.toByte
      }
      RawFrame(
        payload.size.toInt,
        `type`,
        flags,
        data.identifier,
        payload
      )
    }
  }



  /*
    +---------------+
    |Pad Length? (8)|
    +-+-------------+-----------------------------------------------+
    |E|                 Stream Dependency? (31)                     |
    +-+-------------+-----------------------------------------------+
    |  Weight? (8)  |
    +-+-------------+-----------------------------------------------+
    |                   Header Block Fragment (*)                 ...
    +---------------------------------------------------------------+
    |                           Padding (*)                       ...
    +---------------------------------------------------------------+
  */
  case  class Headers(
    identifier: Int,
    dependency: Option[Headers.StreamDependency],
    endStream: Boolean, // No Body Follows
    endHeaders: Boolean,  // Whether or not to Expect Continuation Frame (if false, continuation must directly follow)
    headerBlock: ByteVector,
    padding: Option[ByteVector]
  ) extends Frame
  object Headers{
    val `type` = 0x1

    case class StreamDependency(exclusive: Boolean, dependency: Int, weight: Byte)

    def fromRawFrame(rawFrame: RawFrame): Option[Headers] = 
      rawFrame.`type` match {
        case `type` => 
          val endStream = (rawFrame.flags & (0x01 << 0)) != 0
          val endHeaders = (rawFrame.flags & (0x01 << 2)) != 0
          val padded = (rawFrame.flags & (0x01 << 3)) != 0
          val priority = (rawFrame.flags & (0x01 << 5)) != 0

          (priority, padded) match {
            case (false, false) => 
              Headers(rawFrame.identifier, None, endStream, endHeaders, rawFrame.payload, None).some
            case (true, true) => 
              // This hurts. And is SO inefficient
              val bv = rawFrame.payload
              val padLength = bv.get(0)
              val pad = bv.takeRight(padLength)
              val rest = bv.dropRight(padLength).drop(1)
              val weight = rest.get(4)
              val dependency = rest.slice(0L, 3L).toBitVector
              val exclusive = dependency(0)
              val byteVectorCleared = dependency.drop(1).toByteVector.toArray
              val dependsOnStream = java.nio.ByteBuffer.wrap(byteVectorCleared).getInt()
              val payload = rest.drop(5)
              Headers(
                rawFrame.identifier,
                Some(StreamDependency(exclusive, dependsOnStream, weight)),
                endStream,
                endHeaders,
                payload,
                Some(pad)
              ).some
            case (true, false) => 
              val rest = rawFrame.payload
              val weight = rest.get(4)
              val dependency = rest.slice(0L, 3L).toBitVector
              val exclusive = dependency(0)
              val byteVectorCleared = dependency.drop(1).toByteVector.toArray
              val dependsOnStream = java.nio.ByteBuffer.wrap(byteVectorCleared).getInt()
              val payload = rest.drop(5)
              Headers(
                rawFrame.identifier,
                Some(StreamDependency(exclusive, dependsOnStream, weight)),
                endStream,
                endHeaders,
                payload,
                None
              ).some
            case (false, true) =>
              val bv = rawFrame.payload
              val padLength = bv.get(0)
              val pad = bv.takeRight(padLength)
              val payload = bv.dropRight(padLength)
              Headers(
                rawFrame.identifier,
                None,
                endStream,
                endHeaders,
                payload,
                Some(pad)
              ).some
          }
        case _ => None
      }
  }

  /*
    +-+-------------------------------------------------------------+
    |E|                  Stream Dependency (31)                     |
    +-+-------------+-----------------------------------------------+
    |   Weight (8)  |
    +-+-------------+
  */
  case class Priority(
    identifier: Int, 
    exlusiveStreamDependency: Boolean,
    streamDependency: Int,
    weight: Byte
  ) extends Frame
  object Priority {
    val `type` = 0x2
  }



  /*
    +---------------------------------------------------------------+
    |                        Error Code (32)                        |
    +---------------------------------------------------------------+
  */
  case class RstStream(
    identifier: Int, 
    value: Integer
  )
  object RstStream {
    val `type` = 0x3
  }


  /*
    Payload must be a multiple of 8 octets.
    n* 
    +-------------------------------+
    |       Identifier (16)         |
    +-------------------------------+-------------------------------+
    |                        Value (32)                             |
    +---------------------------------------------------------------+
  */
  case class Settings(
    identifier: Int,
    ack: Boolean,
    list: List[Settings.Setting]
  ) extends Frame
  object Settings {
    val `type` = 0x4
    val Ack = Settings(0x0, true, Nil)

    sealed abstract class Setting(identifier: Short, value: Integer)
    case class SettingsHeaderTableSize(size: Integer) extends Setting(0x0, size)
    case class SettingsEnablePush(isEnabled: Boolean) extends Setting(0x2, if (isEnabled) 1 else 0)
    case class SettingsMaxConcurrentStreams(maxConcurrency: Integer) extends Setting(0x3, maxConcurrency)
    case class SettingsInitialWindowSize(windowSize: Integer) extends Setting(0x4, windowSize)
    case class SettingsMaxFrameSize(frameSize: Integer) extends Setting(0x5, frameSize)
    case class SettingsMaxHeaderListSize(listSize: Int) extends Setting(0x6, listSize)
    // DO NOT PROCESS
    // An endpoint that receives a SETTINGS frame with any unknown or
    // unsupported identifier MUST ignore that setting.
    case class Unknown(identifier: Short, value: Integer) extends Setting(identifier, value)
  }


  /*
    +---------------+
    |Pad Length? (8)|
    +-+-------------+-----------------------------------------------+
    |R|                  Promised Stream ID (31)                    |
    +-+-----------------------------+-------------------------------+
    |                   Header Block Fragment (*)                 ...
    +---------------------------------------------------------------+
    |                           Padding (*)                       ...
    +---------------------------------------------------------------+
  */
  case class PushPromise(identifier: Int, endHeaders: Boolean, promisedStreamId: Int, headerBlock: Array[Byte], padding: Option[Array[Byte]]) extends Frame
  object PushPromise {
    val `type` = 0x5
  }


  /*
    +---------------------------------------------------------------+
    |                                                               |
    |                      Opaque Data (64)                         |
    |                                                               |
    +---------------------------------------------------------------+
  */
  case class Ping(identifier: Int, ack: Boolean, data: Array[Byte]) extends Frame// Always exactly 8 bytes
  object Ping {
    val `type` = 0x6

  }

  /*
    +-+-------------------------------------------------------------+
    |R|                  Last-Stream-ID (31)                        |
    +-+-------------------------------------------------------------+
    |                      Error Code (32)                          |
    +---------------------------------------------------------------+
    |                  Additional Debug Data (*)                    |
    +---------------------------------------------------------------+
  */
  case class GoAway(identifier: Int, lastStreamId: Int, errorCode: Integer, additionalDebugData: Array[Byte]) extends Frame
  object GoAway {
    val `type` = 0x7

  }

  /*
    +-+-------------------------------------------------------------+
    |R|              Window Size Increment (31)                     |
    +-+-------------------------------------------------------------+
  */
  case class WindowUpdate(identifier: Int, windowSizeIncrement: Int) extends Frame
  object WindowUpdate {
    val `type` = 0x8
  }

  /*
    +---------------------------------------------------------------+
    |                   Header Block Fragment (*)                 ...
    +---------------------------------------------------------------+
  */
  case class Continuation(identifier: Int, endHeaders: Boolean, headerBlockFragment: Array[Byte]) extends Frame
  object Continuation {
    val `type` = 0x9
  }



}