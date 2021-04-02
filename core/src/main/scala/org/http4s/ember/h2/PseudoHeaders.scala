package org.http4s.ember.h2

import org.http4s._
import cats.syntax.all._

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
  def requestToHeaders[F[_]](req: Request[F]): List[(String, String, Boolean)] = {
    val path = {
      val s = req.uri.path.renderString
      if (s.isEmpty) "/"
      else s
    }
    val l = List(
      (METHOD, req.method.toString, false),
      (SCHEME, req.uri.scheme.map(_.value).getOrElse("https"), false),
      (PATH, path, false),
      (AUTHORITY, req.uri.authority.map(_.toString).getOrElse(""), false)
    ) ::: req.headers.headers.map(raw => (raw.name.toString, raw.value, org.http4s.Headers.SensitiveHeaders.contains(raw.name)))
    l
  }

  def headersToRequestNoBody(headers: List[(String, String)]): Option[Request[fs2.Pure]] = {
    val method = headers.find(_._1 === METHOD).map(_._2).flatMap(Method.fromString(_).toOption)
    val scheme = headers.find(_._1 === SCHEME).map(_._2).map(Uri.Scheme(_))
    val path = headers.find(_._1 === PATH).map(_._2)
    val authority = extractAuthority(headers)
    val h = Headers(
      headers.filterNot(t => requestPsedo.contains(t._1))
      .map(t => Header.Raw(org.typelevel.ci.CIString(t._1), t._2)):_*
    )
    for {
      m <- method
      p <- path
      u <- Uri.fromString(p).toOption
    } yield Request(m, u.copy(scheme = scheme, authority = authority), HttpVersion.`HTTP/2.0`, h)
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

  def responseToHeaders[F[_]](response: Response[F]): List[(String, String, Boolean)] = {
    (STATUS, response.status.code.toString, false) ::
    response.headers.headers.map(raw => (raw.name.toString, raw.value, org.http4s.Headers.SensitiveHeaders.contains(raw.name)))
  }

  def headersToResponseNoBody(headers: List[(String, String)]): Option[Response[fs2.Pure]] = {
    val status = headers.collectFirstSome{
      case (PseudoHeaders.STATUS, value) => 
        Status.fromInt(value.toInt).toOption
      case (_, _) => None
    }
    val h = Headers(
      headers.filterNot(t => t._1 == PseudoHeaders.STATUS)
      .map(t => Header.Raw(org.typelevel.ci.CIString(t._1), t._2)):_*
    )
    status.map(s => 
      Response(status = s, httpVersion = HttpVersion.`HTTP/2.0`, headers = h)
    )
  }
}