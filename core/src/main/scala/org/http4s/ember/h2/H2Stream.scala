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
  val remoteSettings: F[Frame.Settings.ConnectionSettings],
  val state: Ref[F, H2Stream.State[F]],
  val hpack: Hpack[F],
  val enqueue: cats.effect.std.Queue[F, List[Frame]],
  val onClosed: F[Unit],
  val goAway: H2Error => F[Unit]
){


  // TODO Check Settings to Split Headers into Headers and Continuation
  def sendHeaders(headers: NonEmptyList[(String, String, Boolean)], endStream: Boolean): F[Unit] = 
    state.get.flatMap{ s => 
      s.state match {
        case StreamState.Idle | StreamState.HalfClosedRemote | StreamState.Open  => 
          hpack.encodeHeaders(headers).map(bv => 
            Frame.Headers(id, None, endStream, true, bv, None)
          ).flatMap(f => enqueue.offer(f:: Nil)) <* 
            state.modify{b => 
              val newState: StreamState = (b.state, endStream) match {
                case (StreamState.Idle, false) => StreamState.Open
                case (StreamState.Idle, true) => StreamState.HalfClosedLocal
                case (StreamState.HalfClosedRemote, false) => StreamState.HalfClosedRemote
                case (StreamState.HalfClosedRemote, true) => StreamState.Closed
                case (StreamState.Open, false) => StreamState.Open
                case (StreamState.Open, true) => StreamState.HalfClosedLocal
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
          enqueue.offer(Frame.Data(id, bv, None, endStream):: Nil) >> {
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
          enqueue.offer(Frame.Data(id, head, None, false):: Nil) >> 
          s.writeBlock.get.rethrow >> sendData(tail, endStream)
        }  
      case _ => new Throwable("Stream Was Closed").raiseError
    }
  }

  

  def receiveHeaders(headers: Frame.Headers, continuations: Frame.Continuation*): F[Unit] = state.get.flatMap{
    s => 
    s.state match {
      case StreamState.Open | StreamState.HalfClosedLocal | StreamState.Idle => 
        val block = headers.headerBlock ++ continuations.foldLeft(ByteVector.empty){ case (acc, cont) => acc ++ cont.headerBlockFragment}
        println(headers)
        continuations.foreach(println)
        for {
          h <- hpack.decodeHeaders(block).onError{
            case e => println("Issue in headers $e"); goAway(H2Error.CompressionError)
          }
          // others <- continuations.toList.flatTraverse(c => hpack.decodeHeaders(c.headerBlockFragment).map(_.toList))
          // h = l ++ others
          newstate = if (headers.endStream) s.state match {
            case StreamState.Open => StreamState.HalfClosedRemote // Client
            case StreamState.Idle => StreamState.HalfClosedRemote // Server
            case StreamState.HalfClosedLocal => StreamState.Closed // Client
            case s => s
          } else s.state match {
            case StreamState.Idle => StreamState.Open // Server
            case s => s
          }
          headers <- state.modify(s => 
            (s.copy(state = newstate), s.readHeaders)//, readHeaders = l ::: others ::: s.readHeaders))
          )
          // _ = println(s"headers $headers")
          _ <- headers.complete(Either.right(h))
          _ <- if (newstate == StreamState.Closed) onClosed else Applicative[F].unit
        } yield ()
      case StreamState.HalfClosedRemote | StreamState.Closed =>
        goAway(H2Error.StreamClosed)
      case StreamState.ReservedLocal | StreamState.ReservedRemote =>
        goAway(H2Error.InternalError) // Not Implemented Push promise Support
    }
  }

  def receiveData(data: Frame.Data): F[Unit] = state.get.flatMap{ s => 
    println(s"s: $s data:$data")
    s.state match {
      case StreamState.Open | StreamState.HalfClosedLocal => 
        // println(s"ReceiveData $data")
        val newSize = s.readWindow - data.data.size.toInt
        val newState = if (data.endStream) s.state match {
          case StreamState.Open => StreamState.HalfClosedRemote
          case StreamState.HalfClosedLocal => StreamState.Closed
          case s => s
        } else s.state
        val isClosed = newState == StreamState.Closed

        val needsWindowUpdate = (newSize <= (localSettings.initialWindowSize.windowSize / 2))
        // println(s"s: $id , newSize: $newSize, needsWindowUpdate: $needsWindowUpdate")
        for {
          // settings <- remoteConnectionSettings
          _ <- state.update(s => 
            s.copy(state = newState, readWindow = if (needsWindowUpdate) localSettings.initialWindowSize.windowSize else newSize)
          )
          _ <- s.readBuffer.offer(Either.right(data.data))

          _ <- if (needsWindowUpdate && !isClosed) enqueue.offer(Frame.WindowUpdate(id, localSettings.initialWindowSize.windowSize - newSize) :: Nil) else Applicative[F].unit
          _ <- if (isClosed) onClosed else Applicative[F].unit
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
    _ <- enqueue.offer(rst :: Nil)
    t = new Throwable(s"Sending RstStream, cancelling: $rst")
    _ <- s.writeBlock.complete(Left(t))
    _ <- s.readHeaders.complete(Left(t))
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
    _ <- s.readHeaders.complete(Left(t))
    _ <- s.readBuffer.offer(Left(t))
    _ <- onClosed
  } yield ()

  def receiveRstStream(rst: Frame.RstStream): F[Unit] = for {
    s <- state.modify(s => (s.copy(state = StreamState.Closed), s))
    t = new Throwable(s"Received RstStream, cancelling: $rst")
    _ <- s.writeBlock.complete(Left(t))
    _ <- s.readHeaders.complete(Left(t))
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


  def getHeaders: F[NonEmptyList[(String, String)]] = state.get.flatMap(_.readHeaders.get.rethrow)

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
  case class State[F[_]](state: StreamState, writeWindow: Int, writeBlock: Deferred[F, Either[Throwable, Unit]], readWindow: Int, readHeaders: Deferred[F, Either[Throwable, NonEmptyList[(String, String)]]],  readBuffer: cats.effect.std.Queue[F, Either[Throwable, ByteVector]])
}