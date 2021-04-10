package org.http4s.ember.h2

import cats.effect._
import fs2._
import fs2.concurrent._
import fs2.io.net.Socket
import cats._
import cats.syntax.all._
import scodec.bits._
import cats.data._
import scala.concurrent.duration._

import Frame.Settings.ConnectionSettings.{default => defaultSettings} 

class H2Connection[F[_]](
  host: com.comcast.ip4s.Host,
  port: com.comcast.ip4s.Port,
  connectionType: H2Connection.ConnectionType,
  localSettings: Frame.Settings.ConnectionSettings,
  val mapRef: Ref[F, Map[Int, H2Stream[F]]],
  val state: Ref[F, H2Connection.State[F]], // odd if client, even if server
  val outgoing: cats.effect.std.Queue[F, Chunk[Frame]],
  // val outgoingData: cats.effect.std.Queue[F, Frame.Data], // TODO split data rather than backpressuring frames totally

  val createdStreams: cats.effect.std.Queue[F, Int],
  val closedStreams: cats.effect.std.Queue[F, Int],

  hpack: Hpack[F],
  val streamCreateAndHeaders: Resource[F, Unit], 
  val settingsAck: Deferred[F, Either[Throwable, Frame.Settings.ConnectionSettings]],
  socket: Socket[F],
)(implicit F: Temporal[F]){

  def initiateLocalStream: F[H2Stream[F]] = for {
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
    request <- Deferred[F, Either[Throwable, org.http4s.Request[fs2.Pure]]]
    response <- Deferred[F, Either[Throwable, org.http4s.Response[fs2.Pure]]]
    body <- cats.effect.std.Queue.unbounded[F, Either[Throwable, ByteVector]]
    refState <- Ref.of[F, H2Stream.State[F]](
      H2Stream.State(StreamState.Idle, settings.initialWindowSize.windowSize, writeBlock, localSettings.initialWindowSize.windowSize, request, response, body)
    )
    stream = new H2Stream(id, localSettings, connectionType,  state.get.map(_.remoteSettings), refState, hpack, outgoing, closedStreams.offer(id), goAway)
    _ <- mapRef.update(m => m + (id -> stream))
  } yield stream

  def initiateRemoteStreamById(id: Int): F[H2Stream[F]] = for {
    t <- state.get.map(s => (s.remoteSettings, s.remoteHighestStream))
    (settings, highestStream) = t
    writeBlock <- Deferred[F, Either[Throwable, Unit]]
    request <- Deferred[F, Either[Throwable, org.http4s.Request[fs2.Pure]]]
    response <- Deferred[F, Either[Throwable, org.http4s.Response[fs2.Pure]]]
    body <- cats.effect.std.Queue.unbounded[F, Either[Throwable, ByteVector]]
    refState <- Ref.of[F, H2Stream.State[F]](
      H2Stream.State(StreamState.Idle, settings.initialWindowSize.windowSize, writeBlock, localSettings.initialWindowSize.windowSize, request, response, body)
    )
    stream = new H2Stream(id, localSettings, connectionType, state.get.map(_.remoteSettings), refState, hpack, outgoing, closedStreams.offer(id), goAway)
    _ <- mapRef.update(m => m + (id -> stream))
    _ <- state.update(s => s.copy(highestStream = Math.max(s.highestStream, id), remoteHighestStream = Math.max(s.remoteHighestStream, id)))
  } yield stream

  def goAway(error: H2Error): F[Unit] = {
    state.get.map(_.remoteHighestStream).flatMap{i => 
      val g = error.toGoAway(i)
      outgoing.offer(Chunk.singleton(g))
    } >> 
    H2Connection.KillWithoutMessage().raiseError
  }

  def writeLoop: Stream[F, Nothing] = {
    Stream.fromQueueUnterminatedChunk[F, Frame](outgoing, Int.MaxValue)
      // .groupWithin[F](200, 5.millis)(F)
      .evalMap{
        case g: Frame.GoAway => 
              mapRef.get.flatMap{ m => 
                m.values.toList.traverse_(connection => connection.receiveGoAway(g))
              } >> state.update(s => s.copy(closed = true)).as(g).widen[Frame]
        case otherwise => otherwise.pure[F]
      }
      .debug(formatter = {c => s"Connection $host:$port Write- $c"})
      .chunks
      .evalMap{chunk => 
        def go(chunk: Chunk[Frame]): F[Unit] = state.get.flatMap{ s =>
          val fullDataSize = chunk.foldLeft(0){
            case (init, Frame.Data(_, data, _, _)) => init + data.size.toInt
            case (init, _) => init
          }
          // println(s"Next Write Block Window - data: $fullDataSize window:${s.writeWindow} $s")

          if (fullDataSize <= s.writeWindow) {
            val bv = chunk.foldLeft(ByteVector.empty){ case (acc, frame) => acc ++ Frame.toByteVector(frame)}
            state.update(s => s.copy(writeWindow = {s.writeWindow - fullDataSize})) >>
            socket.isOpen.ifM(
              socket.write(Chunk.byteVector(bv)),
              new Throwable("Socket Closed when attempting to write").raiseError
            )
          } else {
            val list = chunk.toList
            val nonData = list.takeWhile{
              case _ : Frame.Data => false
              case _ => true
            }
            val after = list.dropWhile{
              case _ : Frame.Data => false
              case _ => true
            }

            val bv = nonData.foldLeft(ByteVector.empty){ case (acc, frame) => acc ++ Frame.toByteVector(frame)}
            socket.isOpen.ifM(
              socket.write(Chunk.byteVector(bv)),
              new Throwable("Socket Closed when attempting to write").raiseError
            ) >> 
            s.writeBlock.get.rethrow >> 
            go(Chunk.seq(after))
          }
        }
        go(chunk)
      }
      .drain // TODO Split Frames between Data and Others Hold Data If we are approaching cap --
      //  Currently will backpressure at the data frame till its cleared
  }

  
  def readLoop: Stream[F, Nothing] = {
    def p(acc: ByteVector): Pull[F, Frame, Unit] = {
      if (acc.isEmpty) {
        Pull.eval(socket.read(localSettings.initialWindowSize.windowSize)).flatMap{
          case Some(chunk) => p(chunk.toByteVector)
          case None => println(s"Connection $host:$port readLoop Terminated with empty"); Pull.done 
        }
      } else {
        Frame.RawFrame.fromByteVector(acc) match {
          case Some((raw, leftover)) => 
            Frame.fromRaw(raw) match {
              case Right(frame) => 
                Pull.output1(frame) >>
                p(leftover)
              case Left(e) => 
                s"Connection $host:$port readLoop Terminated invalid Raw to Frame"
                Pull.eval(goAway(e)) >> 
                Pull.done
            }
          case None => 
            Pull.eval(socket.read(localSettings.initialWindowSize.windowSize)).flatMap{
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
        case (c@Frame.Continuation(id, true, _), H2Connection.State(_, _, _, _, _, _, _, Some((h, cs)), None)) => 
          if (h.identifier == id) {
            state.update(s => s.copy(headersInProgress = None)) >> 
            mapRef.get.map(_.get(id)).flatMap{
                case Some(s) => 
                  s.receiveHeaders(h, cs ::: c :: Nil:_*)
                case None => 
                  streamCreateAndHeaders.use(_ => 
                    for {
                      stream <- initiateRemoteStreamById(id)
                      enqueue <- createdStreams.offer(id)
                      _ <- stream.receiveHeaders(h, cs ::: c :: Nil:_*)
                    } yield ()
                  )
              }
          } else {
            println("Invalid Continuation - Protocol Error")
            goAway(H2Error.ProtocolError)
          }
        case (c@Frame.Continuation(id, true, _), H2Connection.State(_, _,_,  _, _, _, _, None, Some((p, cs)))) => 
          if (p.promisedStreamId == id) {
            state.update(s => s.copy(headersInProgress = None)) >> 
            mapRef.get.map(_.get(id)).flatMap{
                case Some(s) => 
                  s.receivePushPromise(p, cs ::: c :: Nil:_*)
                case None => 
                  streamCreateAndHeaders.use(_ => 
                    for {
                      stream <- initiateRemoteStreamById(id)
                      enqueue <- createdStreams.offer(id)
                      _ <- stream.receivePushPromise(p, cs ::: c :: Nil:_*)
                      
                    } yield ()
                  )
              }
          } else {
            println("Invalid Continuation - Protocol Error")
            goAway(H2Error.ProtocolError)
          }
        case (c@Frame.Continuation(id, false, _), H2Connection.State(_, _, _, _, _, _, _, None, Some((h, cs)))) =>
          if (h.identifier == id) {
            state.update(s => s.copy(pushPromiseInProgress = (h, cs ::: c :: Nil).some))
          } else {
            println("Invalid Continuation - Protocol Error")
            goAway(H2Error.ProtocolError)
          }
        
        case (c@Frame.Continuation(id, false, _), H2Connection.State(_,_,  _, _, _, _, _, Some((h, cs)), None)) =>
          if (h.identifier == id) {
            state.update(s => s.copy(headersInProgress = (h, cs ::: c :: Nil).some))
          } else {
            println("Invalid Continuation - Protocol Error")
            goAway(H2Error.ProtocolError)
          }
        case (f, H2Connection.State(_, _, _, _, _, _, _, Some((h, cs)), None)) => 
          // Only Continuation Frames Are Valid While there is a value
          println(s"Continuation for headers in process, retrieved unexpected frame $f")
          goAway(H2Error.ProtocolError)
        case (f, H2Connection.State(_, _, _, _, _, _, _, None, Some(_))) => 
          // Only Continuation Frames Are Valid While there is a value
          println(s"Continuation for push promise in process, retrieved unexpected frame $f")
          goAway(H2Error.ProtocolError)

        case (h@Frame.Headers(i, sd, _, true, headerBlock, _), s) => 
          val size = headerBlock.size.toInt
          if (size > s.remoteSettings.maxFrameSize.frameSize) {
            println("Header Size too large for frame size")
            goAway(H2Error.FrameSizeError)
          } else if (sd.exists(s => s.dependency == i)) {
            goAway(H2Error.ProtocolError)
          } else {
            mapRef.get.map{_.get(i)}.flatMap{
              case Some(s) => 
                s.receiveHeaders(h)
              case None => 
                val isValidToCreate = connectionType match {
                  case H2Connection.ConnectionType.Server => i % 2 != 0
                  case H2Connection.ConnectionType.Client => i % 2 == 0
                }
                if (!isValidToCreate || i <= s.remoteHighestStream) {
                  println(s"Not Valid Stream to Create $i - $isValidToCreate, ${s.highestStream} - Protocol Error")
                  goAway(H2Error.ProtocolError)
                } else {
                  streamCreateAndHeaders.use(_ => 
                    for {
                      stream <- initiateRemoteStreamById(i)
                      enqueue <- createdStreams.offer(i)
                      _ <- stream.receiveHeaders(h)
                      
                    } yield ()
                  )
                }
            }
          }
        case (h@Frame.Headers(i, sd, _, false, headerBlock, _), s) => 
          val size = headerBlock.size.toInt
          if (size > s.remoteSettings.maxFrameSize.frameSize) goAway(H2Error.FrameSizeError)
          else if (sd.exists(s => s.dependency == i)) goAway(H2Error.ProtocolError)
          else {
            state.update(s => s.copy(headersInProgress = Some((h, List.empty))))
          }
        case (h@Frame.PushPromise(attachedTo ,true , i, headerBlock, _), s) => 
          val size = headerBlock.size.toInt
          if (connectionType == H2Connection.ConnectionType.Server) {
            println("Encountered Push Promise Frame a a Server")
            goAway(H2Error.ProtocolError)
          } else if (size > s.remoteSettings.maxFrameSize.frameSize) {
            println("Header Size too large for frame size")
            goAway(H2Error.FrameSizeError)
          } else {
            mapRef.get.map{_.get(i)}.flatMap{
              case Some(s) => 
                s.receivePushPromise(h)
              case None => 
                val isValidToCreate = i % 2 == 0
                if (!isValidToCreate || i <= s.remoteHighestStream) {
                  println(s"Not Valid Stream to Create $i - $isValidToCreate, ${s.remoteHighestStream} - Protocol Error")
                  goAway(H2Error.ProtocolError)
                } else {
                  streamCreateAndHeaders.use(_ => 
                    for {
                      stream <- initiateRemoteStreamById(i)
                      enqueue <- createdStreams.offer(i)
                      _ <- stream.receivePushPromise(h)
                    } yield ()
                  )
                }
            }
          }
        case (h@Frame.PushPromise(i, false, _, headerBlock, _), s) => 
          val size = headerBlock.size.toInt
          if (size > s.remoteSettings.maxFrameSize.frameSize) goAway(H2Error.FrameSizeError)
          else {
            state.update(s => s.copy(pushPromiseInProgress = Some((h, List.empty))))
          }
  
        case (Frame.Continuation(_, _, _), s) => 
          goAway(H2Error.ProtocolError)

        case (settings@Frame.Settings(0,false, _), s) => 
          state.modify{s => 
            val newSettings = Frame.Settings.updateSettings(settings, s.remoteSettings)
            (s.copy(remoteSettings = newSettings), newSettings)
          }.flatMap{settings => println(s"Connection $host:$port Settings- $settings") // TODO cheating
          outgoing.offer(Chunk.singleton(Frame.Settings.Ack)) >> // Ack
          settingsAck.complete(Either.right(settings)).void
        }
        case (Frame.Settings(0, true, _), s) => Applicative[F].unit
        case (Frame.Settings(_, _, _), s) => 
          println("Received Settings Not Oriented at Identifier 0")
          goAway(H2Error.ProtocolError)
        case (g@Frame.GoAway(0, _,_,bv),s) => mapRef.get.flatMap{ m => 
          m.values.toList.traverse_(connection => connection.receiveGoAway(g))
        } >> outgoing.offer(Chunk.singleton(Frame.Ping.ack))
        case (_:Frame.GoAway, _) => 
          goAway(H2Error.ProtocolError)
        case (Frame.Ping(0, false, bv),s) => 
            outgoing.offer(Chunk.singleton(Frame.Ping.ack.copy(data = bv)))
        case (Frame.Ping(0, true, _),s) => Applicative[F].unit
        case (Frame.Ping(x, _, _),s) => 
          // println("Encounteed NonZero Ping - Protocol Error")
          // goAway(H2Error.ProtocolError)
          H2Connection.KillWithoutMessage().raiseError


        case (w@Frame.WindowUpdate(_, 0), _) => 
          println("Encounted 0 Sized Window Update - Procol Error")
          goAway(H2Error.ProtocolError)
        case (w@Frame.WindowUpdate(i, size), s) => 
          i match {
            case 0 => 
              // println("Received Window Update for Connection")
              for {
                newWriteBlock <- Deferred[F, Either[Throwable, Unit]]
                t <- state.modify{s => 
                  val newSize = s.writeWindow + size
                  val sizeValid = newSize <= Int.MaxValue && newSize >= 0 // Less than 2^31-1 and didn't overflow, going negative
                  (s.copy(writeBlock = newWriteBlock, writeWindow = s.writeWindow + size), (s.writeBlock, sizeValid))
                }
                (oldWriteBlock, valid) = t
                _ <- oldWriteBlock.complete(Right(()))
                _ <- {
                  if (!valid) goAway(H2Error.FlowControlError)
                  else outgoing.offer(Chunk.singleton(Frame.Ping.ack))
                }
              } yield ()
            case otherwise => 
              mapRef.get.map(_.get(otherwise)).flatMap{
                case Some(s) => 
                  s.receiveWindowUpdate(w)
                case None => 
                  println(s"Received WindowUpdate for Closed or Idle Stream - $w, $i")
                  goAway(H2Error.ProtocolError)
              }
          }
        
        case (d@Frame.Data(i, data, _, _), st) => 
          val size = data.size.toInt
          if (size > localSettings.maxFrameSize.frameSize) {
            println("Receive Data Size Larger than Allowed Frame Size - Frame Size Error")
            goAway(H2Error.FrameSizeError)
          } else {
            mapRef.get.map(_.get(i)).flatMap{
              case Some(s) => 
                for {
                  st <- state.get
                  newSize = st.readWindow - d.data.size.toInt
                  
                  needsWindowUpdate = (newSize <= (localSettings.initialWindowSize.windowSize / 2))
                  _ <- state.update(s => s.copy(readWindow = if (needsWindowUpdate) localSettings.initialWindowSize.windowSize else newSize.toInt))
                  _ <- if (needsWindowUpdate) outgoing.offer(Chunk.singleton(Frame.WindowUpdate(0, st.remoteSettings.initialWindowSize.windowSize - newSize.toInt))) else Applicative[F].unit
                  _ <- s.receiveData(d)
                } yield ()
              case None => 
                println(s"Received Data Frame for Idle or Closed Stream $i - Protocol Error")
                goAway(H2Error.ProtocolError)
            }
          }
        
        case (rst@Frame.RstStream(i, _), s) => 
          mapRef.get.map(_.get(i)).flatMap{
            case Some(s) => 
              s.receiveRstStream(rst)
            case None => 
              println(s"Received RstStream for Idle or Closed Stream $i - Protocol Error")
              goAway(H2Error.ProtocolError)
          }

        case (Frame.PushPromise(_, _, _, _, _), s) => 
          connectionType match {
            case H2Connection.ConnectionType.Server => 
              println("Received PushPromise as Server - Protocol Error")
              goAway(H2Error.ProtocolError)
            case H2Connection.ConnectionType.Client => Applicative[F].unit // TODO Implement Push Promise Flow
          }
        case (Frame.Priority(i, _, i2, _), s) =>
          if (i == i2) goAway(H2Error.ProtocolError)  // Can't depend on yourself
          else Applicative[F].unit // We Do Nothing with these presently
        case (Frame.Unknown(_), _) => Applicative[F].unit // Ignore Unknown Frames
      }.drain.onFinalizeCase[F]{
        case Resource.ExitCase.Errored(H2Connection.KillWithoutMessage()) => 
        Applicative[F].unit.map(_ => println(s"ReadLoop has received that is should kill")) >> 
          state.update(s => s.copy(closed = true))
        case Resource.ExitCase.Errored(e) => 
          Applicative[F].unit.map(_ => println(s"ReadLoop has errored: $e")) >> 
          goAway(H2Error.InternalError) >> 
          state.update(s => s.copy(closed = true))
        
        case _ => Applicative[F].unit
      } 


}

object H2Connection {
  case class State[F[_]](
    remoteSettings: Frame.Settings.ConnectionSettings,
    writeWindow: Int,
    writeBlock: Deferred[F, Either[Throwable, Unit]],
    readWindow: Int,
    highestStream: Int,
    remoteHighestStream: Int,
    closed: Boolean,
    headersInProgress: Option[(Frame.Headers, List[Frame.Continuation])],
    pushPromiseInProgress: Option[(Frame.PushPromise, List[Frame.Continuation])]
  )

  final case class KillWithoutMessage() extends RuntimeException with scala.util.control.NoStackTrace



  sealed trait ConnectionType
  object ConnectionType {
    case object Server extends ConnectionType
    case object Client extends ConnectionType
  }

}