package org.http4s.ember.h2

import cats.effect._
import cats.effect.std._
import cats.effect.kernel._
import scodec.bits._
import com.twitter.hpack._

object Main extends IOApp {

  def run(args: List[String]): IO[ExitCode] = {
    val maxTableSize = 4096
    val base = "test"
    val encoder = new Encoder(maxTableSize)

    
    // val encoded = HuffmanEncoder.encode(base.getBytes())
    // val bv = ByteVector()
    // val decoded = HuffmanDecoder.decode(encoded)
    // val string = new String(decoded)
    // println(bv.toBin)

    IO.unit.as(ExitCode.Success)
  }

}