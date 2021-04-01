package org.http4s.ember.h2

import cats._
import cats.syntax.all._
import cats.effect._
import cats.effect.syntax.all._
import cats.effect.std._
import cats.effect.kernel._
import scodec.bits._
import com.twitter.hpack._
import fs2._
import fs2.concurrent._
import fs2.io.net._
import fs2.io.net.tls._
import com.comcast.ip4s._
import org.http4s.implicits._

import org.typelevel.ci.CIString
import scala.concurrent.duration._

object Main extends IOApp {

  def run(args: List[String]): IO[ExitCode] = {

    Test.test2[IO].as(ExitCode.Success)
  }

}

object Test {

  def test2[F[_]: Async: Parallel] = {
    H2Client.impl[F].use{ c => 
      val p = c.run(org.http4s.Request[F](
        org.http4s.Method.GET, 
        // uri = uri"https://github.com/"
        uri = uri"https://en.wikipedia.org/wiki/HTTP/2"
        // uri = uri"https://twitter.com/"
        // uri = uri"https://banno.com/"
      ))//.putHeaders(org.http4s.headers.Connection(CIString("keep-alive")) ))
        .use(_.body.compile.drain)
        // .use(_.body.chunks.fold(0){case (i, c) => i + c.size}.evalMap(i => Sync[F].delay(println("Total So Far: $i"))).compile.drain >> Sync[F].delay(println("Body Received")))
        // (p,  p, p).parTupled
        p >> (p,p).parTupled >>
        Temporal[F].sleep(10.second) >> 
        p
        // List.fill(50)(p.attempt).parSequence.flatTap(a => Sync[F].delay(println(a)))
    }
  }
}




