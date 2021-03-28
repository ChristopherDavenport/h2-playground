package org.http4s.ember.h2

import scodec.bits._
import munit.CatsEffectSuite
import cats.syntax.all._
import cats.effect._

class FrameSpec extends CatsEffectSuite {

  test("RawFrame Should Traverse") {
    val init = Frame.RawFrame(
      2,
      Frame.Settings.`type`,
      0x01,
      Int.MaxValue,
      ByteVector(0x00, 0x02)
    )
    val bv = Frame.RawFrame.toByteVector(init)
    val parsed = Frame.RawFrame.fromByteVector(bv).map(_._1)

    assertEquals(parsed, init.some)
  }

  test("RawFrame should return excess data") {
    val init = Frame.RawFrame(
      2,
      Frame.Settings.`type`,
      0x01,
      Int.MaxValue,
      ByteVector(0x00, 0x02, 0x04)
    )
    val bv = Frame.RawFrame.toByteVector(init)
    val parsed = Frame.RawFrame.fromByteVector(bv).map(_._2)

    assertEquals(parsed, ByteVector(0x04).some)
  }

  test("Decode A Data Frame from Raw") {
    val init = Frame.RawFrame(
      2,
      Frame.Data.`type`,
      0x01,
      Int.MaxValue,
      ByteVector(0x00, 0x02)
    )
    val bv = Frame.RawFrame.toByteVector(init)
    val intermediate = Frame.RawFrame.fromByteVector(bv).map(_._1)
    val parsed = intermediate.flatMap(Frame.Data.fromRaw)
    val expected = Frame.Data(Int.MaxValue, ByteVector(0x00, 0x02), None, true)
    

    assertEquals(parsed, expected.some)
  }

  test("Data Frame should traverse"){
    val init = Frame.Data(4, ByteVector(0x02, 0xa0), None, false)
    val encoded = Frame.Data.toRaw(init)
    val back = Frame.Data.fromRaw(encoded)
    assertEquals(back, init.some)
  }

  test("Data Frame should traverse with padding"){
    val init = Frame.Data(4, ByteVector(0x02, 0xa0), Some(ByteVector(0xff, 0xff, 0xff, 0xff)), false)
    val encoded = Frame.Data.toRaw(init)
    val back = Frame.Data.fromRaw(encoded)
    assertEquals(back, init.some)
  }

  test("Headers should traverse"){
    val init = Frame.Headers(7, 
      Some(Frame.Headers.StreamDependency(true, 4, 3)),
      true,
      true, 
      ByteVector(3, 4), 
      Some(ByteVector(1))
    )
    val encoded = Frame.Headers.toRaw(init)
    val back = Frame.Headers.fromRaw(encoded)
    assertEquals(
      back,
      init.some
    )
  }

  test("Priority should traverse"){
    val init = Frame.Priority(5, true, 7, 0x01)
    val encoded = Frame.Priority.toRaw(init)
    val back = Frame.Priority.fromRaw(encoded)
    assertEquals(
      back,
      init.some
    )
  }

  test("RstStream should traverse"){
    val init = Frame.RstStream(4, 73)
    val encoded = Frame.RstStream.toRaw(init)
    val back = Frame.RstStream.fromRaw(encoded)
    assertEquals(
      back,
      init.some
    )
  }

  test("Settings should traverse"){
    val init = Frame.Settings(
      0x0, 
      true, 
      List(
        Frame.Settings.SettingsHeaderTableSize(4096),
        Frame.Settings.SettingsEnablePush(true),
        Frame.Settings.SettingsMaxConcurrentStreams(1024),
        Frame.Settings.SettingsInitialWindowSize(65535),
        Frame.Settings.SettingsMaxFrameSize(16384),
        Frame.Settings.SettingsMaxHeaderListSize(4096),
        Frame.Settings.Unknown(14, 56)
      )
    )
    val encoded = Frame.Settings.toRaw(init)
    val back = Frame.Settings.fromRaw(encoded)
    assertEquals(
      back,
      init.some
    )
  }
  
}