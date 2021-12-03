// import cats._
// import cats.syntax.all._
// import cats.effect._
// import cats.effect.syntax.all._
// import cats.effect.std._
// import cats.effect.kernel._
// import scodec.bits._
// import com.twitter.hpack._
// import fs2._
// import fs2.concurrent._
// import fs2.io.net._
// import fs2.io.net.tls._
// import com.comcast.ip4s._
// import org.http4s.implicits._

// import org.typelevel.ci.CIString
// import scala.concurrent.duration._
// import org.http4s.ember.h2._

// object ClientTest {

//   def test[F[_]: Async: Parallel] = {
//     // Resource.eval(Network[F].tlsContext.insecure).flatMap{tls => 
//     H2Client.impl[F](
//       {(req, resp) => resp.flatMap(r => 
//           Sync[F].delay(Outcome.succeeded(Applicative[F].pure(println(s"Got Push Promise $req, $r - s ${req.attributes.lookup(H2Keys.StreamIdentifier)} ${req.attributes.lookup(H2Keys.PushPromiseInitialStreamIdentifier)}")))))},

//       // {(req, resp) =>Applicative[F].pure(Outcome.Canceled())},
//       // None,
//       // tls,
//       H2Frame.Settings.ConnectionSettings.default
//     )}.use{ c => 
//       val p = c.run(org.http4s.Request[F](
//         org.http4s.Method.GET, 
//         // uri = uri"https://github.com/"
//         uri = uri"https://en.wikipedia.org/wiki/HTTP/2"
//         // uri = uri"https://twitter.com/"
//         // uri = uri"https://banno.com/"
//         // uri = uri"http://http2.golang.org/reqinfo"
//         // uri = uri"http://en.wikipedia.org/wiki/HTTP/2"
//         // uri = uri"http://localhost:8080/"
//         // uri = uri"https://www.nikkei.com/" // PUSH PROMISES
//       ))//.withAttribute(H2Keys.Http2PriorKnowledge, ()))//.putHeaders(org.http4s.headers.Connection(CIString("keep-alive")) ))
//         .use(resp => resp.body.compile.drain >> Sync[F].delay(println(s"Resp $resp")))
//         // .use(_.body.chunks.fold(0){case (i, c) => i + c.size}.evalMap(i => Sync[F].delay(println("Total So Far: $i"))).compile.drain >> Sync[F].delay(println("Body Received")))
//         // (p,  p, p).parTupled
//         p >> p
//         // (p,p,p, p).parTupled
//         // Temporal[F].sleep(10.second) >> 
//         // p >> Temporal[F].sleep(10.second) >> p
//         // Stream(Stream.eval(p.attempt).repeat.take(20000)).parJoin(100).timeout(30.seconds).compile.toList.flatMap{m => 
//         //   Sync[F].delay(println(s"${m.size}"))
//         // }
//         // List.fill(50)(p.attempt).parSequence.flatTap(a => Sync[F].delay(println(a)))
//     }
//   }
// }

// object ClientMain extends IOApp {
//   def run(args: List[String]): IO[ExitCode] = {

//     ClientTest.test[IO]
//       // .use(_ => IO.never)
//       .as(ExitCode.Success)
//   }
// }

