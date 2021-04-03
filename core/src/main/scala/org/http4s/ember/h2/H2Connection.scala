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
  localSettings: Frame.Settings.ConnectionSettings,
  val mapRef: Ref[F, Map[Int, H2Stream[F]]],
  val state: Ref[F, H2Connection.State[F]], // odd if client, even if server
  val outgoing: cats.effect.std.Queue[F, List[Frame]],
  val outgoingData: cats.effect.std.Queue[F, Frame.Data],

  val createdStreams: cats.effect.std.Queue[F, Int],
  val closedStreams: cats.effect.std.Queue[F, Int],

  hpack: Hpack[F],
  val streamCreateAndHeaders: Resource[F, Unit], 
  val settingsAck: Deferred[F, Either[Throwable, Frame.Settings.ConnectionSettings]],
  socket: Socket[F],
){

  def initiateStream: F[H2Stream[F]] = state.modify(s => 
    (s.copy(highestStreamInitiated = s.highestStreamInitiated + 2), s.highestStreamInitiated)
  ).flatMap(initiateStreamById(_))

  def initiateStreamById(id: Int): F[H2Stream[F]] = for {
    settings <- state.get.map(_.remoteSettings)
    writeBlock <- Deferred[F, Either[Throwable, Unit]]
    headers <- Deferred[F, Either[Throwable, List[(String, String)]]]
    body <- cats.effect.std.Queue.unbounded[F, Either[Throwable, ByteVector]]
    refState <- Ref.of[F, H2Stream.State[F]](
      H2Stream.State(StreamState.Idle, settings.initialWindowSize.windowSize, writeBlock, localSettings.initialWindowSize.windowSize, headers, body)
    )
  } yield new H2Stream(id, localSettings, state.get.map(_.remoteSettings), refState, hpack, outgoing, closedStreams.offer(id))

  def writeLoop: Stream[F, Nothing] = 
    (Stream.eval(outgoing.take) ++
      Stream.eval(outgoing.tryTake)
      .repeat
      .takeWhile(_.isDefined)
      .unNone
    )
      .flatMap(l => Stream.emits(l))
      .debug(formatter = {c => s"Connection $host:$port Write- $c"})
      .chunkMin(1024, true)
      .evalMap{chunk => 
        val bv = chunk.foldLeft(ByteVector.empty){ case (acc, frame) => acc ++ Frame.toByteVector(frame)}
        socket.isOpen.ifM(
          socket.write(Chunk.byteVector(bv)),
          new Throwable("Socket Closed when attempting to write").raiseError
        )
        
      }.repeat.drain // TODO Split Frames between Data and Others Hold Data If we are approaching cap

  def readLoop: Stream[F, Nothing] = {
    def p(acc: ByteVector): Pull[F, Frame, Unit] = {
      if (acc.isEmpty) {
        Pull.eval(socket.read(65536)).flatMap{
          case Some(chunk) => p(chunk.toByteVector)
          case None => println(s"Connection $host:$port readLoop Terminated with empty"); Pull.done 
        }
      } else {
        Frame.RawFrame.fromByteVector(acc) match {
          case Some((raw, leftover)) => 
            Frame.fromRaw(raw) match {
              case Some(frame) => Pull.output1(frame) >> p(leftover)
              case None => 
                p(leftover) // Ignore Unrecognized frames
            }
          case None => 
            Pull.eval(socket.read(65536)).flatMap{
              case Some(chunk) => 
                p(acc ++ chunk.toByteVector)
              case None =>  println(s"Connection $host:$port readLoop Terminated with $acc");  Pull.done 
            }
            
        }
      }
    }
    p(ByteVector.empty).stream
  }
      .debug(formatter = {c => s"Connection $host:$port Read- $c"})
      .evalTap{
        case settings@Frame.Settings(0,false, _) => 
          state.modify{s => 
            val newSettings = Frame.Settings.updateSettings(settings, s.remoteSettings)
            (s.copy(remoteSettings = newSettings), newSettings)
          }.flatMap{settings => println(s"Connection $host:$port Settings- $settings") // TODO cheating
          outgoing.offer(Frame.Settings.Ack :: Nil) >> // Ack
          settingsAck.complete(Either.right(settings)).void
        }
        case Frame.Settings(0, true, _) => Applicative[F].unit
        case Frame.Settings(_, _, _) => 
          state.get.flatMap{s =>  
            outgoing.offer(Frame.GoAway(0, s.highestStreamInitiated, H2Error.ProtocolError.value, None) :: Nil)
          }
        case g@Frame.GoAway(_, _,_,bv) => mapRef.get.flatMap{ m => 
          m.values.toList.traverse_(connection => connection.receiveGoAway(g))
        } >> outgoing.offer(Frame.Ping.ack.copy(data = bv) :: Nil)
        case Frame.Ping(_, false, bv) => 
          outgoing.offer(Frame.Ping.ack.copy(data = bv) :: Nil)
        case Frame.Ping(_, true, _) => Applicative[F].unit


        case w@Frame.WindowUpdate(i, size) => 
          i match {
            case 0 => 
              for {
                newWriteBlock <- Deferred[F, Either[Throwable, Unit]]
                oldWriteBlock <- state.modify(s => (s.copy(writeBlock = newWriteBlock, writeWindow = s.writeWindow + size), s.writeBlock))
                _ <- oldWriteBlock.complete(Right(()))
                _ <- outgoing.offer(Frame.Ping.ack :: Nil)
              } yield ()
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
              streamCreateAndHeaders.use(_ => 
                for {
                  stream <- initiateStreamById(i)
                  _ <- mapRef.update(m => m.get(i).fold(m.+(i -> stream))(_ => m))
                  _ <- stream.receiveHeaders(h)
                  enqueue <- createdStreams.offer(i)
                } yield ()
              )
          }
        case Frame.Continuation(_, _, _) => Applicative[F].unit // TODO header regions need to be identified and chained
        
        case d@Frame.Data(i, _, _, _) => 
          mapRef.get.map(_.get(i)).flatMap{
            case Some(s) => 
              for {
                st <- state.get
                newSize = st.readWindow - d.data.size.toInt
                
                needsWindowUpdate = (newSize <= (localSettings.initialWindowSize.windowSize / 2))
                // _ = println(s"newSize: $newSize, needsWindowUpdate: $needsWindowUpdate")
                _ <- state.update(s => s.copy(readWindow = if (needsWindowUpdate) localSettings.initialWindowSize.windowSize else newSize.toInt))
                _ <- s.receiveData(d)
                _ <- if (needsWindowUpdate) outgoing.offer(Frame.WindowUpdate(0, localSettings.initialWindowSize.windowSize - newSize.toInt):: Nil) else Applicative[F].unit
              } yield ()
            case None => 
              println(s"Data: No Stream Exists... $i")
              Applicative[F].unit
          }
        case rst@Frame.RstStream(i, _) => 
          mapRef.get.map(_.get(i)).flatMap{
            case Some(s) => 
              s.receiveRstStream(rst)
            case None => 
              println(s"RstStream No Stream Exists... $i")
              Applicative[F].unit
          }

        case Frame.PushPromise(_, _, _, _, _) => Applicative[F].unit // TODO Implement Push Promise Flow
        case Frame.Priority(_, _, _, _) => Applicative[F].unit // We Do Nothing with these presently
      }.drain.handleErrorWith{
        case e => Stream.eval(Applicative[F].unit.map(_ => println(s"ReadLoop has errored: $e"))).drain
      } ++ Stream.eval(state.update(s => s.copy(closed = true))).drain


}

object H2Connection {
  case class State[F[_]](remoteSettings: Frame.Settings.ConnectionSettings, writeWindow: Int, writeBlock: Deferred[F, Either[Throwable, Unit]], readWindow: Int, highestStreamInitiated: Int, closed: Boolean)
  // sealed trait ConnectionType
  // object ConnectionType {
  //   case object Server extends ConnectionType
  //   case object Client extends ConnectionType
  // }

}