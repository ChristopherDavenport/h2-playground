package org.http4s.ember.h2

import java.io.InputStream
import java.io.ByteArrayInputStream
import com.twitter.hpack.HeaderListener
import java.nio.charset.StandardCharsets
import scala.collection.mutable.ListBuffer
import org.http4s._
import fs2._
import cats._
import cats.effect.{ApplicativeThrow => _, _}
import cats.syntax.all._
import org.http4s.ember.h2.Frame.Continuation
import org.http4s.ember.h2.Frame.PushPromise
import org.http4s.ember.h2.Frame.GoAway
import org.http4s.ember.h2.Frame.Ping
import org.http4s.ember.h2.Frame.WindowUpdate
import org.http4s.ember.h2.Frame.Data
import org.http4s.ember.h2.Frame.Priority
import org.http4s.ember.h2.Frame.Settings

object Decoder {
  
  // No Partials. We must concatenate frames to get the input array
  def decodeHeaders[F[_]: Sync](tDecoder: com.twitter.hpack.Decoder, array: Array[Byte]): F[List[(String, String)]] = Sync[F].delay{
    var buffer = new ListBuffer[(String, String)]
    val is = new ByteArrayInputStream(array)
    val listener = new HeaderListener{
      def addHeader(name: Array[Byte], value: Array[Byte], sensitive: Boolean): Unit = {
        buffer.addOne(
          new String(name, StandardCharsets.ISO_8859_1) -> new String(value, StandardCharsets.ISO_8859_1)
        )
      }
    }

    tDecoder.decode(is, listener)
    tDecoder.endHeaderBlock()

    buffer.toList
  }


  private def getAuthority(headers: List[(String, String)]): Option[Uri.Authority] = {
    headers.collectFirstSome{
      case (PseudoHeaders.Authority, value) => 
        val index = value.indexOf(":")
        if (index > 0 && index < value.length) {
          Option(Uri.Authority(userInfo = None, host = Uri.RegName(value.take(index - 1)), port = value.drop(index).toInt.some))
        } else Option.empty
    }

  }

  // This is Server therefore the only acceptable frame for a request is a Headers frame
  def requestFromFrames[F[_]: Async](tDecoder: com.twitter.hpack.Decoder,  streamState: Ref[F, StreamState], getFrame: F[Chunk[Frame]]): F[Request[F]] = {

    
    
    def onIdle(frame: Frame): F[Request[F]] = frame match {
      
      case PushPromise(_, _, promisedStreamId, headerBlock, padding) => ???
      case GoAway(_, lastStreamId, errorCode, additionalDebugData) => ???
      case Ping(_, ack, data) => ???
      case WindowUpdate(_, windowSizeIncrement) => ???
      case Settings(_, ack, list) => ???
      case Data(identifier, data, pad, endStream) =>
        ???
      case Frame.Headers(_, _,_, _,  headerBlock, padding) =>
        decodeHeaders[F](tDecoder, headerBlock.toArray).flatMap{
          case headers => 
            val methodO = headers.collectFirstSome{ case (PseudoHeaders.Method, value) => Method.fromString(value).toOption }
            val schemeO = headers.collectFirstSome{ case (PseudoHeaders.Scheme, value) => org.http4s.Uri.Scheme.fromString(value).toOption}
            val pathO = headers.collectFirst{ case (PseudoHeaders.Path, value) => value}
            val authorityO = getAuthority(headers)
            (methodO, schemeO, pathO, authorityO).mapN{
              case (method, scheme, path, authority) => 
                Request[F]().pure[F]
            }.getOrElse(ApplicativeThrow[F].raiseError(new Throwable("Incomplete Information")))
        }
      case Continuation(_, endHeaders, headerBlockFragment) => ???
      case Priority(_, exlusiveStreamDependency, streamDependency, weight) =>
        ???
    }

    ???
  }
}