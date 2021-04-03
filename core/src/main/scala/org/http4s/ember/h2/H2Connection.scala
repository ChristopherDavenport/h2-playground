package org.http4s.ember.h2

import cats.effect._
import fs2._
import fs2.concurrent._
import fs2.io.net.Socket
import cats._
import cats.syntax.all._
import scodec.bits._
import cats.data._

class H2Connection[F[_]: Concurrent](
  host: com.comcast.ip4s.Host,
  port: com.comcast.ip4s.Port,
  connectionType: H2Connection.ConnectionType,
  localSettings: Frame.Settings.ConnectionSettings,
  val mapRef: Ref[F, Map[Int, H2Stream[F]]],
  val state: Ref[F, H2Connection.State[F]], // odd if client, even if server
  val outgoing: cats.effect.std.Queue[F, List[Frame]],
  // val outgoingData: cats.effect.std.Queue[F, Frame.Data], // TODO split data rather than backpressuring frames totally

  val createdStreams: cats.effect.std.Queue[F, Int],
  val closedStreams: cats.effect.std.Queue[F, Int],

  hpack: Hpack[F],
  val streamCreateAndHeaders: Resource[F, Unit], 
  val settingsAck: Deferred[F, Either[Throwable, Frame.Settings.ConnectionSettings]],
  socket: Socket[F],
){

  def initiateStream: F[H2Stream[F]] = for {
    t <- state.modify{s => 
      val highestIsEven = s.highestStream % 2 == 0
      val newHighest = connectionType match {
        case H2Connection.ConnectionType.Server => if (highestIsEven) s.highestStream + 2 else s.highestStream + 1
        case H2Connection.ConnectionType.Client => if (highestIsEven) s.highestStream + 1 else s.highestStream + 2
      } 
        (s.copy(highestStream = newHighest) , (s.remoteSettings, newHighest))
    }
    (settings, id) = t
    writeBlock <- Deferred[F, Either[Throwable, Unit]]
    headers <- Deferred[F, Either[Throwable, NonEmptyList[(String, String)]]]
    body <- cats.effect.std.Queue.unbounded[F, Either[Throwable, ByteVector]]
    refState <- Ref.of[F, H2Stream.State[F]](
      H2Stream.State(StreamState.Idle, settings.initialWindowSize.windowSize, writeBlock, localSettings.initialWindowSize.windowSize, headers, body)
    )
  } yield new H2Stream(id, localSettings, state.get.map(_.remoteSettings), refState, hpack, outgoing, closedStreams.offer(id), goAway)

  def initiateStreamById(id: Int): F[H2Stream[F]] = for {
    t <- state.get.map(s => (s.remoteSettings, s.highestStream))
    (settings, highestStream) = t
    writeBlock <- Deferred[F, Either[Throwable, Unit]]
    headers <- Deferred[F, Either[Throwable, NonEmptyList[(String, String)]]]
    body <- cats.effect.std.Queue.unbounded[F, Either[Throwable, ByteVector]]
    refState <- Ref.of[F, H2Stream.State[F]](
      H2Stream.State(StreamState.Idle, settings.initialWindowSize.windowSize, writeBlock, localSettings.initialWindowSize.windowSize, headers, body)
    )
  } yield new H2Stream(id, localSettings, state.get.map(_.remoteSettings), refState, hpack, outgoing, closedStreams.offer(id), goAway)

  def goAway(error: H2Error): F[Unit] = {
    state.get.map(_.highestStream).flatMap{i => 
      val g = error.toGoAway(i)
      outgoing.offer(g :: Nil)
    }
  }

  def writeLoop: Stream[F, Nothing] = 
    (Stream.eval(outgoing.take) ++
      Stream.eval(outgoing.tryTake)
      .repeat
      .takeWhile(_.isDefined)
      .unNone
    )
      .flatMap(l => Stream.emits(l))
      .evalMap{
        case g: Frame.GoAway => 
              mapRef.get.flatMap{ m => 
                m.values.toList.traverse_(connection => connection.receiveGoAway(g))
              } >> state.update(s => s.copy(closed = true)).as(g).widen[Frame]
        case otherwise => otherwise.pure[F]
      }
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
      .evalMap(f => state.get.map(s => (f, s)))
      .evalTap{
        // Headers and Continuation Frames are Stateful
        // Headers if not closed MUST
        case (c@Frame.Continuation(id, true, _), H2Connection.State(_, _, _, _, _, _, Some((h, cs)))) => 
          if (h.identifier == id) {
            state.update(s => s.copy(headersInProgress = None)) >> 
            mapRef.get.map(_.get(id)).flatMap{
                case Some(s) => 
                  s.receiveHeaders(h, cs ::: c :: Nil:_*)
                case None => 
                  streamCreateAndHeaders.use(_ => 
                    for {
                      stream <- initiateStreamById(id)
                      _ <- mapRef.update(m => m.get(id).fold(m.+(id -> stream))(_ => m))
                      _ <- stream.receiveHeaders(h, cs ::: c :: Nil:_*)
                      enqueue <- createdStreams.offer(id)
                    } yield ()
                  )
              }
          } else goAway(H2Error.ProtocolError)
        
        case (c@Frame.Continuation(id, false, _), H2Connection.State(_, _, _, _, _, _, Some((h, cs)))) =>
          if (h.identifier == id) {
            state.update(s => s.copy(headersInProgress = (h, cs ::: c :: Nil).some))
          } else goAway(H2Error.ProtocolError)
        case (_, H2Connection.State(_, _, _, _, _, _, Some((h, cs)))) => 
          // Only Connection Frames Are Valid While there is a 
          goAway(H2Error.ProtocolError)

        case (h@Frame.Headers(i, _, _, true, headerBlock, _), s) => 
          val size = headerBlock.size.toInt
          if (size > s.remoteSettings.maxFrameSize.frameSize) {
            goAway(H2Error.FrameSizeError)
          } else {
            mapRef.get.map{_.get(i)}.flatMap{
              case Some(s) => 
                s.receiveHeaders(h)
              case None => 
                val isValidToCreate = connectionType match {
                  case H2Connection.ConnectionType.Server => i % 2 != 0
                  case H2Connection.ConnectionType.Client => i % 2 == 0
                }
                if (!isValidToCreate || i > s.highestStream) {
                  goAway(H2Error.ProtocolError)
                } else {
                  streamCreateAndHeaders.use(_ => 
                    for {
                      stream <- initiateStreamById(i)
                      _ <- mapRef.update(m => m.get(i).fold(m.+(i -> stream))(_ => m))
                      _ <- stream.receiveHeaders(h)
                      enqueue <- createdStreams.offer(i)
                    } yield ()
                  )
                }
            }
          }
        case (h@Frame.Headers(i, _, _, false, headerBlock, _), s) => 
          val size = headerBlock.size.toInt
          if (size > s.remoteSettings.maxFrameSize.frameSize) goAway(H2Error.FrameSizeError)
          else {
            state.update(s => s.copy(headersInProgress = Some((h, List.empty))))
          }
        case (Frame.Continuation(_, _, _), s) => 
          goAway(H2Error.ProtocolError)

        case (settings@Frame.Settings(0,false, _), s) => 
          state.modify{s => 
            val newSettings = Frame.Settings.updateSettings(settings, s.remoteSettings)
            (s.copy(remoteSettings = newSettings), newSettings)
          }.flatMap{settings => println(s"Connection $host:$port Settings- $settings") // TODO cheating
          outgoing.offer(Frame.Settings.Ack :: Nil) >> // Ack
          settingsAck.complete(Either.right(settings)).void
        }
        case (Frame.Settings(0, true, _), s) => Applicative[F].unit
        case (Frame.Settings(_, _, _), s) => 
          goAway(H2Error.ProtocolError)
        case (g@Frame.GoAway(0, _,_,bv),s) => mapRef.get.flatMap{ m => 
          m.values.toList.traverse_(connection => connection.receiveGoAway(g))
        } >> outgoing.offer(Frame.Ping.ack.copy(data = bv) :: Nil)
        case (_:Frame.GoAway, _) => 
          goAway(H2Error.ProtocolError)
        case (Frame.Ping(0, false, bv),s) => 
          if (bv.fold(true)(_.size.toInt == 8)) {
            outgoing.offer(Frame.Ping.ack.copy(data = bv) :: Nil)
          } else {
            goAway(H2Error.FrameSizeError)
          }
        case (Frame.Ping(0, true, _),s) => Applicative[F].unit
        case (Frame.Ping(x, _, _),s) => goAway(H2Error.ProtocolError)


        case (w@Frame.WindowUpdate(i, size), s) => 
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
                  goAway(H2Error.ProtocolError)
              }
          }
        
        case (d@Frame.Data(i, data, _, _), s) => 
          val size = data.size.toInt
          if (size > s.remoteSettings.maxFrameSize.frameSize) goAway(H2Error.FrameSizeError)
          else {
            mapRef.get.map(_.get(i)).flatMap{
              case Some(s) => 
                for {
                  st <- state.get
                  newSize = st.readWindow - d.data.size.toInt
                  
                  needsWindowUpdate = (newSize <= (localSettings.initialWindowSize.windowSize / 2))
                  _ <- state.update(s => s.copy(readWindow = if (needsWindowUpdate) localSettings.initialWindowSize.windowSize else newSize.toInt))
                  _ <- s.receiveData(d)
                  _ <- if (needsWindowUpdate) outgoing.offer(Frame.WindowUpdate(0, localSettings.initialWindowSize.windowSize - newSize.toInt):: Nil) else Applicative[F].unit
                } yield ()
              case None => 
                goAway(H2Error.ProtocolError)
            }
          }
        
        case (rst@Frame.RstStream(i, _), s) => 
          mapRef.get.map(_.get(i)).flatMap{
            case Some(s) => 
              s.receiveRstStream(rst)
            case None => 
              goAway(H2Error.ProtocolError)
          }

        case (Frame.PushPromise(_, _, _, _, _), s) => 
          connectionType match {
            case H2Connection.ConnectionType.Server => goAway(H2Error.ProtocolError)
            case H2Connection.ConnectionType.Client => Applicative[F].unit // TODO Implement Push Promise Flow
          }
        case (Frame.Priority(_, _, _, _), s) => Applicative[F].unit // We Do Nothing with these presently
      }.drain.handleErrorWith{
        case e => Stream.eval(Applicative[F].unit.map(_ => println(s"ReadLoop has errored: $e"))).drain
      } ++ Stream.eval(goAway(H2Error.InternalError) >> state.update(s => s.copy(closed = true))).drain


}

object H2Connection {
  case class State[F[_]](
    remoteSettings: Frame.Settings.ConnectionSettings,
    writeWindow: Int,
    writeBlock: Deferred[F, Either[Throwable, Unit]],
    readWindow: Int,
    highestStream: Int,
    closed: Boolean,
    headersInProgress: Option[(Frame.Headers, List[Frame.Continuation])]
  )

  sealed trait ConnectionType
  object ConnectionType {
    case object Server extends ConnectionType
    case object Client extends ConnectionType
  }

}