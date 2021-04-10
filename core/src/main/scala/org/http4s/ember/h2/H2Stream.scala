package org.http4s.ember.h2

import cats.data._
import cats.effect._
import fs2._
import fs2.concurrent._
import fs2.io.net.Socket
import cats._
import cats.syntax.all._
import scodec.bits._

// Will eventually hold client/server through single interface matching that of the designed paradigm
// in StreamState
class H2Stream[F[_]: Concurrent](
  val id: Int,
  localSettings: Frame.Settings.ConnectionSettings,
  connectionType: H2Connection.ConnectionType,
  val remoteSettings: F[Frame.Settings.ConnectionSettings],
  val state: Ref[F, H2Stream.State[F]],
  val hpack: Hpack[F],
  val enqueue: cats.effect.std.Queue[F, Chunk[Frame]],
  val onClosed: F[Unit],
  val goAway: H2Error => F[Unit]
){

  def sendPushPromise(originating: Int, headers: NonEmptyList[(String, String, Boolean)]): F[Unit] = {
    connectionType match {
      case H2Connection.ConnectionType.Server => 
        state.get.flatMap{ s=> 
          s.state match {
            case StreamState.Idle => 
              for {
                h <- hpack.encodeHeaders(headers)
                frame = Frame.PushPromise(originating, true, id, h, None)
                _ <- state.update(s => s.copy(state = StreamState.ReservedLocal))
                _ <- enqueue.offer(Chunk.singleton(frame))
              } yield ()
            case _ => new Throwable("Push Promises are only allowed on an idle Stream").raiseError
          }
        }
      case H2Connection.ConnectionType.Client => 
        new Throwable("Clients Are Not Allowed To Send PushPromises").raiseError
    } 
    
  }


  // TODO Check Settings to Split Headers into Headers and Continuation
  def sendHeaders(headers: NonEmptyList[(String, String, Boolean)], endStream: Boolean): F[Unit] = 
    state.get.flatMap{ s => 
      s.state match {
        case StreamState.Idle | StreamState.HalfClosedRemote | StreamState.Open | StreamState.ReservedLocal  => 
          hpack.encodeHeaders(headers).map(bv => 
            Frame.Headers(id, None, endStream, true, bv, None)
          ).flatMap(f => enqueue.offer(Chunk.singleton(f))) <* 
            state.modify{b => 
              val newState: StreamState = (b.state, endStream) match {
                case (StreamState.Idle, false) => StreamState.Open
                case (StreamState.Idle, true) => StreamState.HalfClosedLocal
                case (StreamState.HalfClosedRemote, false) => StreamState.HalfClosedRemote
                case (StreamState.HalfClosedRemote, true) => StreamState.Closed
                case (StreamState.Open, false) => StreamState.Open
                case (StreamState.Open, true) => StreamState.HalfClosedLocal
                case (StreamState.ReservedLocal, true) => StreamState.Closed
                case (StreamState.ReservedLocal, false) => StreamState.HalfClosedRemote
                case (s, _) => s // Hopefully Impossible
              }
              (b.copy(state = newState), newState)
            }.flatMap{state =>
              if (state == StreamState.Closed) onClosed else Applicative[F].unit
            }.void
        case _ => new Throwable("Stream Was Closed").raiseError
      }
    }
    
  def sendData(bv: ByteVector, endStream: Boolean): F[Unit] = state.get.flatMap{ s => 
    s.state match {
      case StreamState.Open | StreamState.HalfClosedRemote => 
        if (bv.size.toInt <= s.writeWindow){ 
          enqueue.offer(Chunk.singleton(Frame.Data(id, bv, None, endStream))) >> {
            state.modify{s => 
              val newState = if (endStream) {
                  s.state match {
                    case StreamState.Open => StreamState.HalfClosedLocal
                    case StreamState.HalfClosedRemote => StreamState.Closed
                    case state => state // Ruh-roh
                  }
                } else s.state
              (
              s.copy(
                state = newState,
                writeWindow = {s.writeWindow - bv.size.toInt},
              ),
              newState
              )
            }.flatMap( state => 
              if (state == StreamState.Closed) onClosed else Applicative[F].unit
            )
          }
        } else {
          val head = bv.take(s.writeWindow)
          val tail = bv.drop(s.writeWindow)
          enqueue.offer(Chunk.singleton(Frame.Data(id, head, None, false))) >> 
          s.writeBlock.get.rethrow >> sendData(tail, endStream)
        }  
      case _ => new Throwable("Stream Was Closed").raiseError
    }
  }

  def receiveHeaders(headers: Frame.Headers, continuations: Frame.Continuation*): F[Unit] = state.get.flatMap{
    s => 
    s.state match {
      case StreamState.Open | StreamState.HalfClosedLocal | StreamState.Idle | StreamState.ReservedRemote => 
        val block = headers.headerBlock ++ continuations.foldLeft(ByteVector.empty){ case (acc, cont) => acc ++ cont.headerBlockFragment}
        for {
          h <- hpack.decodeHeaders(block).onError{
            case e => println(s"Issue in headers $e"); goAway(H2Error.CompressionError)
          }
          newstate = if (headers.endStream) s.state match {
            case StreamState.Open => StreamState.HalfClosedRemote // Client
            case StreamState.Idle => StreamState.HalfClosedRemote // Server
            case StreamState.HalfClosedLocal => StreamState.Closed // Client
            case StreamState.ReservedRemote => StreamState.Closed
            case s => s
          } else s.state match {
            case StreamState.Idle => StreamState.Open // Server
            case StreamState.ReservedRemote => StreamState.HalfClosedLocal
            case s => s
          }
          t <- state.modify(s => 
            (s.copy(state = newstate), (s.request, s.response))
          )
          (request, response) = t
          _ <- connectionType match {
            case H2Connection.ConnectionType.Client => 
              PseudoHeaders.headersToResponseNoBody(h) match {
                case Some(resp) => 
                  response.complete(Either.right(resp.withAttribute(H2Keys.StreamIdentifier, id))) >> 
                  resp.contentLength.traverse(length => state.update(s => s.copy(contentLengthCheck = Some((length, 0))))) >>
                  {
                    if (newstate == StreamState.Closed) onClosed else Applicative[F].unit
                  }
                case None => 
                  println("Headers Unable to be parsed")
                  rstStream(H2Error.ProtocolError)
              }
            case H2Connection.ConnectionType.Server => 
              PseudoHeaders.headersToRequestNoBody(h) match {
                case Some(req) => 
                  request.complete(Either.right(req.withAttribute(H2Keys.StreamIdentifier, id))) >>
                  req.contentLength.traverse(length => state.update(s => s.copy(contentLengthCheck = Some((length, 0))))) >>
                  {
                    if (newstate == StreamState.Closed) onClosed else Applicative[F].unit
                  }
                case None => 
                  println("Headers Unable to be parsed")
                  rstStream(H2Error.ProtocolError)
              }
          }
        } yield ()
      case StreamState.HalfClosedRemote | StreamState.Closed =>
        goAway(H2Error.StreamClosed)
      case StreamState.ReservedLocal =>
        goAway(H2Error.ProtocolError)
    }
  }


  def receivePushPromise(headers: Frame.PushPromise, continuations: Frame.Continuation*): F[Unit] =  state.get.flatMap{ 
    s => 
    connectionType match {
      case H2Connection.ConnectionType.Client => 
        s.state match {
          case StreamState.Idle => 
            val block = headers.headerBlock ++ continuations.foldLeft(ByteVector.empty){ case (acc, cont) => acc ++ cont.headerBlockFragment}
            for {
              h <- hpack.decodeHeaders(block).onError{
                case e => println(s"Issue in headers $e"); goAway(H2Error.CompressionError)
              }
              _ <- state.update(s => s.copy(state = StreamState.ReservedRemote))
              _ <- PseudoHeaders.headersToRequestNoBody(h) match {
                case Some(req) => 
                  s.request.complete(Either.right(
                      req.withAttribute(H2Keys.StreamIdentifier, id)
                        .withAttribute(H2Keys.PushPromiseInitialStreamIdentifier, headers.identifier)
                  )).void
                case None => rstStream(H2Error.ProtocolError)
              }
            } yield ()

          case _ => goAway(H2Error.ProtocolError)
        }
      case H2Connection.ConnectionType.Server => 
        goAway(H2Error.ProtocolError)
    }
  }

  def receiveData(data: Frame.Data): F[Unit] = state.get.flatMap{ s => 
    s.state match {
      case StreamState.Open | StreamState.HalfClosedLocal => 
        val newSize = s.readWindow - data.data.size.toInt
        val newState = if (data.endStream) s.state match {
          case StreamState.Open => StreamState.HalfClosedRemote
          case StreamState.HalfClosedLocal => StreamState.Closed
          case s => s
        } else s.state
        val sizeReadOk = if (data.endStream){
            s.contentLengthCheck.forall{ case (max, current) => max === (current + data.data.size)}
        } else true

        val isClosed = newState == StreamState.Closed

        val needsWindowUpdate = (newSize <= (localSettings.initialWindowSize.windowSize / 2))
        for {
          _ <- state.update(s => 
            s.copy(
              state = newState,
              readWindow = if (needsWindowUpdate) localSettings.initialWindowSize.windowSize else newSize,
              contentLengthCheck = s.contentLengthCheck.map{ case (max, current) => (max, current + data.data.size)}
            )
          )
          _ <- if (sizeReadOk) s.readBuffer.offer(Either.right(data.data)) else rstStream(H2Error.ProtocolError)

          _ <- if (needsWindowUpdate && !isClosed && sizeReadOk) enqueue.offer(Chunk.singleton(Frame.WindowUpdate(id, localSettings.initialWindowSize.windowSize - newSize))) else Applicative[F].unit
          _ <- if (isClosed && sizeReadOk) onClosed else Applicative[F].unit
        } yield ()
      case StreamState.Idle => 
        goAway(H2Error.ProtocolError)
      case StreamState.HalfClosedRemote | StreamState.Closed =>
        rstStream(H2Error.StreamClosed)
      case StreamState.ReservedLocal | StreamState.ReservedRemote =>
        goAway(H2Error.InternalError) // Not Implemented Push promise Support
    }
  }

  def rstStream(error: H2Error): F[Unit] = {
    val rst = error.toRst(id)
    for {
    s <- state.modify(s => (s.copy(state = StreamState.Closed), s))
    _ <- enqueue.offer(Chunk.singleton(rst))
    t = new Throwable(s"Sending RstStream, cancelling: $rst")
    _ <- s.writeBlock.complete(Left(t))
    _ <- s.request.complete(Left(t))
    _ <- s.response.complete(Left(t))
    _ <- s.readBuffer.offer(Left(t))
    _ <- onClosed
  } yield ()

  }

  // Broadcast Frame
  // Will eventually allow us to know we can retry if we are above the processed window declared
  def receiveGoAway(goAway: Frame.GoAway): F[Unit] = for {
    s <- state.modify(s => (s.copy(state = StreamState.Closed), s))
    t = new Throwable(s"Received GoAway, cancelling: $goAway")
    _ <- s.writeBlock.complete(Left(t))
    _ <- s.request.complete(Left(t))
    _ <- s.response.complete(Left(t))
    _ <- s.readBuffer.offer(Left(t))
    _ <- onClosed
  } yield ()

  def receiveRstStream(rst: Frame.RstStream): F[Unit] = for {
    s <- state.modify(s => (s.copy(state = StreamState.Closed), s))
    t = new Throwable(s"Received RstStream, cancelling: $rst")
    _ <- s.writeBlock.complete(Left(t))
    _ <- s.request.complete(Left(t))
    _ <- s.response.complete(Left(t))
    _ <- s.readBuffer.offer(Left(t))
    _ <- onClosed
  } yield ()

  // Important for telling folks we can send more data
  def receiveWindowUpdate(window: Frame.WindowUpdate): F[Unit] = for {
    newWriteBlock <- Deferred[F, Either[Throwable, Unit]]
    t <- state.modify{s => 
      val newSize = s.writeWindow + window.windowSizeIncrement
      val sizeValid = newSize <= Int.MaxValue && newSize >= 0 // Less than 2^31-1 and didn't overflow, going negative
      (s.copy(writeBlock = newWriteBlock, writeWindow = newSize), (s.writeBlock, sizeValid))
    }
    (oldWriteBlock, valid) = t

    _ <- {
      if (!valid) rstStream(H2Error.FlowControlError)
      else oldWriteBlock.complete(Right(())).void
    }
  } yield ()


  def getRequest: F[org.http4s.Request[fs2.Pure]] = state.get.flatMap(_.request.get.rethrow)
  def getResponse: F[org.http4s.Response[fs2.Pure]] = state.get.flatMap(_.response.get.rethrow)

  def readBody: Stream[F, Byte] = {
    def p: Pull[F, Byte, Unit] = 
      Pull.eval(state.get).flatMap{ state => 
          val closed = (state.state == StreamState.HalfClosedRemote || state.state == StreamState.Closed)
          if (closed) {
            def p2: Pull[F, Byte, Unit] = Pull.eval(state.readBuffer.tryTake).flatMap{
              case Some(Right(s)) => Pull.output(Chunk.byteVector(s)) >> p2
              case Some(Left(e)) => Pull.raiseError(e)
              case None => Pull.done
            }
            p2
          } else {
            def p2: Pull[F, Byte, Unit] = Pull.eval(state.readBuffer.tryTake).flatMap{
              case Some(Right(s)) => Pull.output(Chunk.byteVector(s)) >> p2
              case Some(Left(e)) => Pull.raiseError(e)
              case None => p
            }
            Pull.eval(state.readBuffer.take).flatMap{
              case Right(b) => Pull.output(Chunk.byteVector(b))
              case Left(e) => Pull.raiseError(e)
            } >> p2
          }
      }
    p.stream
  }

}

object H2Stream {
  case class State[F[_]](
    state: StreamState,
    writeWindow: Int,
    writeBlock: Deferred[F, Either[Throwable, Unit]],
    readWindow: Int,
    request: Deferred[F, Either[Throwable, org.http4s.Request[fs2.Pure]]],
    response: Deferred[F, Either[Throwable, org.http4s.Response[fs2.Pure]]],
    readBuffer: cats.effect.std.Queue[F, Either[Throwable, ByteVector]],
    contentLengthCheck: Option[(Long, Long)]
  )
}