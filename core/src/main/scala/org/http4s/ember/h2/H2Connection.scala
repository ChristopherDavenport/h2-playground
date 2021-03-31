package org.http4s.ember.h2

import cats.effect._
import fs2._
import fs2.concurrent._
import fs2.io.net.Socket
import cats._
import cats.syntax.all._
import scodec.bits._

class H2Connection[F[_]: Concurrent](
  host: com.comcast.ip4s.Host,
  port: com.comcast.ip4s.Port,
  val mapRef: Ref[F, Map[Int, H2Stream[F]]],
  state: Ref[F, H2Connection.State], // odd if client, even if server
  val outgoing: cats.effect.std.Queue[F, List[Frame]],
  hpack: Hpack[F],
  val settingsAck: Deferred[F, Either[Throwable, Unit]],
  socket: Socket[F],
){

  def initiateStream: F[H2Stream[F]] = state.modify(s => 
    (s.copy(highestStreamInitiated = s.highestStreamInitiated + 2), s.highestStreamInitiated)
  ).flatMap(initiateStreamById(_))

  def initiateStreamById(id: Int): F[H2Stream[F]] = for {
    settings <- state.get.map(_.settings)
    readBlock <- Deferred[F, Either[Throwable, Unit]]
    writeBlock <- Deferred[F, Either[Throwable, Unit]]
    headers <- Deferred[F, Either[Throwable, List[(String, String)]]]
    body <- cats.effect.std.Queue.unbounded[F, ByteVector]
    refState <- SignallingRef.of[F, H2Stream.State[F]](
      H2Stream.State(StreamState.Idle, settings.initialWindowSize.windowSize, writeBlock, settings.initialWindowSize.windowSize, headers, body)
    )
  } yield new H2Stream(id, state.get.map(_.settings), refState, hpack, outgoing)

  def writeLoop: Stream[F, Nothing] = 
    (Stream.eval(outgoing.take) ++
      Stream.eval(outgoing.tryTake)
      .repeat
      .takeWhile(_.isDefined)
      .unNone
    )
      .flatMap(l => Stream.emits(l))
      .chunkMin(1024, true)
      // .debug(formatter = {c => s"Connection $host:$port Write- $c"})
      .evalMap{chunk => 
        val bv = chunk.foldLeft(ByteVector.empty){ case (acc, frame) => acc ++ Frame.toByteVector(frame)}
        // socket.isOpen
        socket.write(Chunk.byteVector(bv)) // TODO regroup if that wasn't the issue
      }.drain ++ writeLoop

  def readLoop: Stream[F, Nothing] = {
    def p(acc: ByteVector): Pull[F, Frame, Unit] = {
      if (acc.isEmpty) {
        Pull.eval(socket.read(65536)).flatMap{
          case Some(chunk) => p(chunk.toByteVector)
          case None => println("readLoop Terminated with empty"); Pull.done 
        }
      } else {
        Frame.RawFrame.fromByteVector(acc) match {
          case Some((raw, leftover)) => 
            Frame.fromRaw(raw) match {
              case Some(frame) => Pull.output1(frame) >> p(leftover)
              case None => 
                Pull.raiseError(new Throwable(s"Protocol Failure, could not convert $raw to frame"))
            }
          case None => 
            Pull.eval(socket.read(65536)).flatMap{
              case Some(chunk) => 
                // println(s"Looping with incomplete frame $acc $chunk")
                p(acc ++ chunk.toByteVector)
              case None =>  println(s"readLoop Terminated with ${acc.decodeUtf8}");  Pull.done 
            }
            
        }
      }
    }
    p(ByteVector.empty).stream
  }
      // .debug(formatter = {c => s"Connection $host:$port Read- $c"})
      .evalTap{
        case settings@Frame.Settings(0,false, _) => 
          state.update(s => s.copy(settings = Frame.Settings.updateSettings(settings, s.settings))) >>
          outgoing.offer(Frame.Settings(0, true, List.empty) :: Nil) >> // Ack
          settingsAck.complete(Either.right(())).void
        case Frame.Settings(0, true, _) => Applicative[F].unit
        case Frame.Settings(_, _, _) => 
          state.get.flatMap{s =>  
            outgoing.offer(Frame.GoAway(0, s.highestStreamInitiated, H2Error.ProtocolError.value, None) :: Nil)
          }
        case Frame.GoAway(_, _,_,_) => Applicative[F].unit
        case Frame.Ping(_, _, _) => Applicative[F].unit

        case w@Frame.WindowUpdate(i, _) => 
          i match {
            case 0 => ???
            case otherwise => 
              mapRef.get.map(_.get(otherwise)).flatMap{
                case Some(s) => 
                  s.receiveWindowUpdate(w)
                case None => 
                  println(s"No Stream Exists... $i")
                  Applicative[F].unit
              }
          }
          

        case h@Frame.Headers(i, _, _, _, _, _) => 
          mapRef.get.map(_.get(i)).flatMap{
            case Some(s) => 
              s.receiveHeaders(h)
            case None => 
              println(s"No Stream Exists... $i")
              Applicative[F].unit
          }
        case d@Frame.Data(i, _, _, _) => 
          mapRef.get.map(_.get(i)).flatMap{
            case Some(s) => 
              s.receiveData(d)
            case None => 
              println(s"No Stream Exists... $i")
              Applicative[F].unit
          }
        case Frame.Continuation(_, _, _) => Applicative[F].unit
        case Frame.RstStream(_, _) => Applicative[F].unit

        case Frame.PushPromise(_, _, _, _, _) => Applicative[F].unit
        case Frame.Priority(_, _, _, _) => Applicative[F].unit
      }.drain


}

object H2Connection {
  case class State(settings: Frame.Settings.ConnectionSettings, highestStreamInitiated: Int)
  // sealed trait ConnectionType
  // object ConnectionType {
  //   case object Server extends ConnectionType
  //   case object Client extends ConnectionType
  // }

}