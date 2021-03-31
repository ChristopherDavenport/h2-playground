package org.http4s.ember.h2

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
  val currentConnectionSettings: F[Frame.Settings.ConnectionSettings],
  val state: SignallingRef[F, H2Stream.State[F]],
  val hpack: Hpack[F],
  val enqueue: cats.effect.std.Queue[F, List[Frame]]
){


  // TODO Check Settings to Split Headers into Headers and Continuation
  def sendHeaders(headers: List[(String, String, Boolean)], endStream: Boolean): F[Unit] = 
    state.get.flatMap{ s => 
      s.state match {
        case StreamState.Idle  => 
          hpack.encodeHeaders(headers).map(bv => 
            Frame.Headers(id, None, endStream, true, bv, None)
          ).flatMap(f => enqueue.offer(f:: Nil)) <* state.update(b => b.copy(state = if (endStream) StreamState.HalfClosedLocal else StreamState.Open))
        case _ => ???
      }
    }
    
  def sendData(bv: ByteVector, endStream: Boolean): F[Unit] = state.get.flatMap{ s => 
    s.state match {
      case StreamState.Open | StreamState.HalfClosedRemote => 
        if (bv.size.toInt <= s.writeWindow){ 
          enqueue.offer(Frame.Data(id, bv, None, endStream):: Nil) >> {
            state.update(s => 
              s.copy(
                state = {if (endStream) {
                  s.state match {
                    case StreamState.Open => StreamState.HalfClosedLocal
                    case StreamState.HalfClosedRemote => StreamState.Closed
                    case state => state // Ruh-roh
                  }
                } else s.state},
                writeWindow = {s.writeWindow - bv.size.toInt},
              )
            )
          }
        } else {
          s.writeBlock.get.rethrow >> sendData(bv, endStream)
        }  
      case _ => ???
    }

  }

  

  def receiveHeaders(headers: Frame.Headers, continuations: Frame.Continuation*): F[Unit] = state.get.flatMap{
    s => 
    s.state match {
      case StreamState.Open | StreamState.HalfClosedLocal => 
        for {
          l <- hpack.decodeHeaders(headers.headerBlock)
          others <- continuations.toList.flatTraverse(c => hpack.decodeHeaders(c.headerBlockFragment))
          h = l ::: others
          newstate = if (headers.endStream) s.state match {
            case StreamState.Open => StreamState.HalfClosedRemote
            case StreamState.HalfClosedLocal => StreamState.Closed
            case s => s
          } else s.state
          headers <- state.modify(s => 
            (s.copy(state = newstate), s.readHeaders)//, readHeaders = l ::: others ::: s.readHeaders))
          )
          _ <- headers.complete(Either.right(h))

        } yield ()
      case _ => ???
    }
  }

  def receiveData(data: Frame.Data): F[Unit] = state.get.flatMap{ s => 
    s.state match {
      case StreamState.Open | StreamState.HalfClosedLocal => 
        // println(s"ReceiveData $data")
        val newSize = s.readWindow - data.data.size.toInt
        val newState = if (data.endStream) s.state match {
          case StreamState.Open => StreamState.HalfClosedRemote
          case StreamState.HalfClosedLocal => StreamState.Closed
          case s => s
        } else s.state
        for {
          
          _ <- state.update(s => 
            s.copy(state = newState, readWindow = newSize)
          )
          _ <- s.readBuffer.offer(data.data)

          _ <- enqueue.offer(Frame.WindowUpdate(id, data.data.size.toInt) :: Frame.WindowUpdate(0, data.data.size.toInt) :: Nil)
        } yield ()
      case otherwise =>
        println(s"Unexpected: $s - $data")
        Applicative[F].unit
    }
  }

  

  // Important for telling folks we can send more data
  def receiveWindowUpdate(window: Frame.WindowUpdate): F[Unit] = for {
    newWriteBlock <- Deferred[F, Either[Throwable, Unit]]
    oldWriteBlock <- state.modify(s => (s.copy(writeBlock = newWriteBlock, writeWindow = s.writeWindow + window.windowSizeIncrement), s.writeBlock))
    _ <- oldWriteBlock.complete(Right(()))
  } yield ()


  def getHeaders: F[List[(String, String)]] = state.get.flatMap(_.readHeaders.get.rethrow)

  def readBody: Stream[F, Byte] = {
    def p: Pull[F, Byte, Unit] = 
      Pull.eval(state.get).flatMap{ state => 
          val closed = (state.state == StreamState.HalfClosedRemote || state.state == StreamState.Closed)
          if (closed) {
            def p2: Pull[F, Byte, Unit] = Pull.eval(state.readBuffer.tryTake).flatMap{
              case Some(s) => Pull.output(Chunk.byteVector(s)) >> p2
              case None => Pull.done
            }
            p2
          } else {
            def p2: Pull[F, Byte, Unit] = Pull.eval(state.readBuffer.tryTake).flatMap{
              case Some(s) => Pull.output(Chunk.byteVector(s)) >> p2
              case None => p
            }
            Pull.eval(state.readBuffer.take).flatMap(b => Pull.output(Chunk.byteVector(b))) >> p2
          }
      }
    p.stream
  }

}

object H2Stream {
  case class State[F[_]](state: StreamState, writeWindow: Int, writeBlock: Deferred[F, Either[Throwable, Unit]], readWindow: Int, readHeaders: Deferred[F, Either[Throwable, List[(String, String)]]],  readBuffer: cats.effect.std.Queue[F, ByteVector])
}