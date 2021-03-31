package org.http4s.ember.h2

import cats.syntax.all._
import cats.effect._
import scodec.bits._
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets



object Encoder {

  import org.http4s.Request
  def requestToHeaders[F[_]](req: Request[F]): List[(String, String, Boolean)] = {
     val l = List(
      (":method", req.method.toString, false),
      (":scheme", req.uri.scheme.map(_.value).getOrElse("https"), false),
      (":path", req.uri.path.renderString, false),
      (":authority", req.uri.authority.map(_.toString).getOrElse(""), false)
    ) ::: req.headers.headers.map(raw => (raw.name.toString, raw.value, org.http4s.Headers.SensitiveHeaders.contains(raw.name)))
    println(s"Encoded: $l")
    l
  }

  
  def encodeRequest[F[_]: Async](tEncoder: com.twitter.hpack.Encoder, id: Int)(req: Request[F]): F[List[Frame]] = Sync[F].delay{
    val list = List(
      (":method", req.method.toString, false),
      (":scheme", req.uri.scheme.map(_.toString).getOrElse("https"), false),
      (":path", req.uri.path.renderString, false),
      (":authority", req.uri.authority.map(_.toString).getOrElse(""), false)
    ) ::: req.headers.headers.map(raw => (raw.name.toString, raw.value, org.http4s.Headers.SensitiveHeaders.contains(raw.name)))
    for {
      header <- encodeHeaders(tEncoder, list).map(encoded => Frame.Headers(id, None, false, true, encoded, None))
      body <- req.body.chunks.map(chunk => 
        Frame.Data(id, chunk.toByteVector, None,false)
      ).compile.to(List)
    } yield header :: body.widen[Frame] ::: Frame.Data(id, ByteVector.empty, None, true) :: Nil
  }.flatten

  def encodeHeaders[F[_]: Sync](tEncoder: com.twitter.hpack.Encoder, headers: List[(String, String, Boolean)]): F[ByteVector] = Sync[F].delay{
    val os = new ByteArrayOutputStream(1024)
    headers.foreach{ h => 
      tEncoder.encodeHeader(os, h._1.getBytes(StandardCharsets.ISO_8859_1), h._2.getBytes(StandardCharsets.ISO_8859_1), h._3)
    }
    ByteVector.view(os.toByteArray())
  }
}