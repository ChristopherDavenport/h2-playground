package org.http4s.ember.h2

import org.http4s._
import cats._
import cats.syntax.all._
import org.typelevel.ci._
import cats.data._

/** HTTP/2 pseudo headers */
object PseudoHeaders {
  // Request pseudo headers
  val METHOD = ":method"
  val SCHEME = ":scheme"
  val PATH = ":path"
  val AUTHORITY = ":authority"
  val requestPsedo = Set(
    METHOD, SCHEME, PATH, AUTHORITY
  )

  import org.http4s.Request
  def requestToHeaders[F[_]](req: Request[F]): NonEmptyList[(String, String, Boolean)] = {
    val path = {
      val s = req.uri.path.renderString
      if (s.isEmpty) "/"
      else s
    }
    val l = NonEmptyList.of(
      (METHOD, req.method.toString, false),
      (SCHEME, req.uri.scheme.map(_.value).getOrElse("https"), false) ::
      (PATH, path, false) ::
      (AUTHORITY, req.uri.authority.map(_.toString).getOrElse(""), false) ::
      req.headers.headers.map(raw => (raw.name.toString, raw.value, org.http4s.Headers.SensitiveHeaders.contains(raw.name))):_*
    )
    l
  }

  def headersToRequestNoBody(hI: NonEmptyList[(String, String)]): Option[Request[fs2.Pure]] = {
    // TODO duplicate check - only these psuedo headers, and no duplicates
    val headers: List[(String, String)] = hI.toList
    val method = findWithNoDuplicates(headers)(_._1 === METHOD).map(_._2).flatMap(Method.fromString(_).toOption)
    val scheme = findWithNoDuplicates(headers)(_._1 === SCHEME).map(_._2).map(Uri.Scheme(_))
    val path = findWithNoDuplicates(headers)(_._1 === PATH).map(_._2)
    val authority = extractAuthority(headers)
    val h = Headers(
      headers.filterNot(t => requestPsedo.contains(t._1))
      .map(t => Header.Raw(org.typelevel.ci.CIString(t._1), t._2)):_*
    )
    for {
      m <- method
      p <- path
      _ <- Alternative[Option].guard(p.nonEmpty) // Not Allowed to be empty in http/2
      u <- Uri.fromString(p).toOption
      s <- scheme // Required
    } yield Request(m, u.copy(scheme = s.some, authority = authority), HttpVersion.`HTTP/2.0`, h)
  }

  def extractAuthority(headers: List[(String, String)]): Option[Uri.Authority] = {
    headers.collectFirstSome{
      case (PseudoHeaders.AUTHORITY, value) => 
        val index = value.indexOf(":")
        if (index > 0 && index < value.length) {
          Option(Uri.Authority(userInfo = None, host = Uri.RegName(value.take(index)), port = value.drop(index + 1).toInt.some))
        } else Option.empty
      case (_, _) => None
    }
  }

  // Response pseudo header
  val STATUS = ":status"

  def responseToHeaders[F[_]](response: Response[F]): NonEmptyList[(String, String, Boolean)] = {
    NonEmptyList(
      (STATUS, response.status.code.toString, false),
      response.headers.headers
      .map(raw => (raw.name.toString.toLowerCase, raw.value, org.http4s.Headers.SensitiveHeaders.contains(raw.name)))
    )
  }

  def headersToResponseNoBody(headers: NonEmptyList[(String, String)]): Option[Response[fs2.Pure]] = {
    // TODO Duplicate Check
    val statusL = headers.collect{
      case (PseudoHeaders.STATUS, value) => 
        Status.fromInt(value.toInt).toOption
      case (_, _) => None
    }
    if (statusL.size === 1) {
      val h = Headers(
        headers.filterNot(t => t._1 == PseudoHeaders.STATUS)
        .map(t => Header.Raw(org.typelevel.ci.CIString(t._1), t._2)):_*
      )
      val status = statusL.head // These lets us fail if duplicate is seen
      status.map(s => 
        Response(status = s, httpVersion = HttpVersion.`HTTP/2.0`, headers = h)
      )
    } else None
  }

  def findWithNoDuplicates[A](l: List[A])(bool: A => Boolean): Option[A] = {
    l.foldLeft(Either.right[Unit, Option[A]](None)){
      case (Left(e), a) => Left(e)
      case (Right(Some(a)), next) => 
        if (bool(next)) Left(())
        else Right(Some(a))
      case (Right(None), next) => 
        if (bool(next)) Right(Some(next))
        else Right(None)
    }.toOption.flatten

  }
}