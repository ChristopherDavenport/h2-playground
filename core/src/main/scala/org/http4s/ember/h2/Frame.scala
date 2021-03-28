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
    length: Int, // 3 bytes is within int range -- 16,777,216 16 mb max frame, bigger isn't necessarily better
    `type`: Byte,
    flags: Byte,
    identifier: Int, // 31 bit Integer
    payload: ByteVector
  )

  object RawFrame {

    def fromByteVector(bv: ByteVector): Option[(RawFrame, ByteVector)] = {
      if (bv.length >= 9) {
        val length =  (bv(2) & 0xFF) | ((bv(1) & 0xFF) << 8) | ((bv(0) & 0xFF) << 16)
        if (bv.length >= 9 + length) {
          val `type` = bv(3)
          val flags = bv(4)
          val identifier =  (bv(8) & 0xFF) | ((bv(7) & 0xFF) << 8) | ((bv(6) & 0xFF) << 16) | ((bv(5) & 0xFF) << 24)
          (
            RawFrame(
              length,
              `type`, 
              flags,
              identifier,
              bv.drop(9).take(length)
            ),
            bv.drop(9 + length)
          ).some
        } else None
      } else None
    }


    // Network Byte Order is Big Endian, so Highest Identifier is First
    def toByteVector(raw: RawFrame): ByteVector = {
      // 3
      val zero = ((raw.length >> 16) & 0xff).toByte
      val one = ((raw.length >> 8) & 0xff).toByte
      val two = ((raw.length >> 0) & 0xff).toByte

      // 2
      val t = raw.`type`
      val f = raw.flags

      // 4
      val iZero = ((raw.identifier >> 24) & 0xff).toByte
      val iOne = ((raw.identifier >> 16) & 0xff).toByte
      val iTwo = ((raw.identifier >> 8) & 0xff).toByte
      val iThree = ((raw.identifier) & 0xff).toByte

      ByteVector(
        zero, one, two, 
        t, f, 
        iZero, iOne, iTwo, iThree
      ) ++ raw.payload
    }
  }

  def fromRaw(rawFrame: RawFrame): Option[Frame] = {
    rawFrame.`type` match {
      case Data.`type` => Data.fromRaw(rawFrame)
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
    def fromRaw(rawFrame: RawFrame): Option[Data] = {
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

    def toRaw(data: Data): RawFrame = {
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
    val `type`: Byte = 0x1

    case class StreamDependency(exclusive: Boolean, dependency: Int, weight: Byte)

    def fromRaw(rawFrame: RawFrame): Option[Headers] = 
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
              val s0 = rest.get(0)
              val s1 = rest.get(1)
              val s2 = rest.get(2)
              val s3 = rest.get(3)
              val weight = rest.get(4)
              val mod0 = s0 & ~(1 << 7)
              val dependsOnStream = ((mod0 << 24) + (s1 << 16) + (s2 << 8) + (s3 << 0))
              val exclusive = (s0 & (0x01 << 7)) != 0
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
              val s0 = rest.get(0)
              val s1 = rest.get(1)
              val s2 = rest.get(2)
              val s3 = rest.get(3)
              val weight = rest.get(4)
              val mod0 = s0 & ~(1 << 7)
              val dependsOnStream = ((mod0 << 24) + (s1 << 16) + (s2 << 8) + (s3 << 0))
              val exclusive = (s0 & (0x01 << 7)) != 0
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
              val payload = bv.dropRight(padLength).drop(1)
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
    def toRaw(headers: Headers): RawFrame = {
      val flags = {
        var init = 0
        if (headers.endStream) init = (init | (1 << 0))
        if (headers.endHeaders) init = (init | (1 << 2))
        if (headers.padding.isDefined) init = (init | (1 << 3))
        if (headers.dependency.isDefined) init = (init | (1 << 5))
        init
      }.toByte

      val body = (headers.padding, headers.dependency) match {
        case (None, None) => headers.headerBlock
        case (Some(pad), None) => 
          ByteVector(pad.length.toByte) ++ headers.headerBlock ++ 
          pad
        case (padO, Some(dependency)) => 
          val dep0 = ((dependency.dependency >> 24) & 0xff).toByte
          val dep1 = ((dependency.dependency >> 16) & 0xff).toByte
          val dep2 = ((dependency.dependency >> 8) & 0xff).toByte
          val dep3 = ((dependency.dependency >> 0) & 0xff).toByte
          val modDep0 = (if (dependency.exclusive) dep0 | (1 << 7) else dep0 & ~(1 << 7)).toByte
          val base = ByteVector(modDep0, dep1, dep2, dep3, dependency.weight) ++ headers.headerBlock
          padO match {
            case None => base
            case Some(pad) => 
              ByteVector(pad.length.toByte) ++ base ++ 
              pad
          }
      }


      RawFrame(
        body.size.toInt,
        `type`,
        flags,
        headers.identifier,
        body
      )
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
    val `type`: Byte = 0x2
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
    val `type`: Byte = 0x3

    def toRaw(rst: RstStream): RawFrame = {
      RawFrame(4, `type`, 0, rst.identifier, ByteVector.fromInt(rst.value.toInt))
    }

    def fromRaw(raw: RawFrame): Option[RstStream] = {
      if (raw.`type` == `type`) RstStream(raw.identifier, raw.payload.toInt(false, ByteOrdering.BigEndian)).some
      else None
    }
  }


  /*
    Payload must be a multiple of 6 octets.
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
    val `type`: Byte = 0x4
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
    val `type`: Byte = 0x5
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
    val `type`: Byte = 0x6

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
    val `type`: Byte = 0x7

  }

  /*
    +-+-------------------------------------------------------------+
    |R|              Window Size Increment (31)                     |
    +-+-------------------------------------------------------------+
  */
  case class WindowUpdate(identifier: Int, windowSizeIncrement: Int) extends Frame
  object WindowUpdate {
    val `type`: Byte = 0x8
  }

  /*
    +---------------------------------------------------------------+
    |                   Header Block Fragment (*)                 ...
    +---------------------------------------------------------------+
  */
  case class Continuation(identifier: Int, endHeaders: Boolean, headerBlockFragment: Array[Byte]) extends Frame
  object Continuation {
    val `type`: Byte = 0x9
  }



}