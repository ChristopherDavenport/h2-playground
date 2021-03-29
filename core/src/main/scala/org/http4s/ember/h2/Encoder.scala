package org.http4s.ember.h2

import cats.syntax.all._
import cats.effect._
import scodec.bits._
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

object Encoder {

  import org.http4s.Request
  def encodeRequest[F[_]: Async](tEncoder: com.twitter.hpack.Encoder, id: Int)(req: Request[F]): F[List[Frame]] = Sync[F].delay{
    val list = List(
      (":method", req.method.toString, false),
      (":scheme", req.uri.scheme.map(_.toString).getOrElse("https"), false),
      (":path", req.uri.path.renderString, false),
      (":authority", req.uri.authority.map(_.toString).getOrElse(" "), false)
    ) ::: req.headers.headers.map(raw => (raw.name.toString, raw.value, org.http4s.Headers.SensitiveHeaders.contains(raw.name)))
    println(s"List: $list")
    val encoded = encodeHeaders(tEncoder, list)
    val header: Frame = Frame.Headers(id, None, false, true, encoded, None)
    
    req.body.chunks.map(chunk => 
      Frame.Data(id, chunk.toByteVector, None,false)
    ).compile.to(List).map{ body => 
      header :: body.widen[Frame] ::: Frame.Data(id, ByteVector.empty, None, true) :: Nil
    }
  }.flatten

  def encodeHeaders(tEncoder: com.twitter.hpack.Encoder, headers: List[(String, String, Boolean)]): ByteVector = {
    val os = new ByteArrayOutputStream(1024)
    headers.foreach{ h => 
      tEncoder.encodeHeader(os, h._1.getBytes(StandardCharsets.ISO_8859_1), h._2.getBytes(StandardCharsets.ISO_8859_1), h._3)
    }
    ByteVector(os.toByteArray())
  }
}