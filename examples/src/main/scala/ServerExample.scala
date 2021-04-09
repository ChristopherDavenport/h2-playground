import cats._
import cats.syntax.all._
import cats.effect._
import cats.effect.syntax.all._
import cats.effect.std._
import cats.effect.kernel._
import scodec.bits._
import com.twitter.hpack._
import fs2._
import fs2.concurrent._
import fs2.io.net._
import fs2.io.net.tls._
import com.comcast.ip4s._
import org.http4s.implicits._

import org.typelevel.ci.CIString
import scala.concurrent.duration._
import org.http4s.ember.h2._



object ServerTest {
  import fs2._
  import org.http4s._
  import org.http4s.implicits._
  import org.http4s.dsl._
  import java.nio.file.{Paths, Path}
  import com.comcast.ip4s._
  val resp = Response[fs2.Pure](Status.Ok).withEntity("Hello World!")
  def simpleApp[F[_]: Monad] = {
    val dsl = new Http4sDsl[F]{}; import dsl._
    HttpRoutes.of[F]{ 
      case _ -> Root / "foo" =>
        Response[F](Status.Ok).withEntity("Foo Endpoint").pure[F]

      case _  => 
          resp.covary[F] // URI needs authority scheme, etc
          .withAttribute(H2Keys.PushPromises, Request[Pure](Method.GET, uri"https://localhost:8080/foo") :: Nil)
          .pure[F]
        

    }.orNotFound
  }

  def test[F[_]: Async: Parallel] = for {
    // sg <- Network[F].socketGroup()
    wd <- Resource.eval(Sync[F].delay(System.getProperty("user.dir")))
    currentFilePath <- Resource.eval(Sync[F].delay(Paths.get(wd, "keystore.jks")))
    secondFilePath <- Resource.eval(Sync[F].delay(Paths.get(wd, "examples/keystore.jks")))
    tlsContext <- Resource.eval(
      Network[F].tlsContext.fromKeyStoreFile(currentFilePath, "changeit".toCharArray, "changeit".toCharArray)
        .handleErrorWith(_ => Network[F].tlsContext.fromKeyStoreFile(secondFilePath, "changeit".toCharArray, "changeit".toCharArray))
    )
    _ <- H2Server.impl(
      Ipv4Address.fromString("0.0.0.0").get,
      Port.fromInt(8080).get,
      tlsContext, 
      simpleApp[F],
      Frame.Settings.ConnectionSettings.default
    )
  } yield ()
  
}

object ServerMain extends IOApp {

  def run(args: List[String]): IO[ExitCode] = {

    ServerTest.test[IO]
      .use(_ => IO.never)
      .as(ExitCode.Success)
  }

}

