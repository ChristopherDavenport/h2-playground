package org.http4s.ember.h2.hpack

object StaticTable {

  private val EMPTY = ""

  def access(i: Int) = {
    require(i >= 1 && i < 61, s"Invalid Static Table Access, must be between 1 and 61: got $i")
    value(i -1)
  }

  private val value = scala.collection.immutable.ArraySeq(
    ":authority" -> EMPTY,
    ":method" -> "GET",
    ":method" -> "POST",
    ":path" -> "/",
    ":path" -> "/index.html",
    ":scheme" -> "http",
    ":scheme" -> "https",
    ":status" -> "200",
    ":status" -> "204",
    ":status" -> "206",
    ":status" -> "304",
    ":status" -> "400",
    ":status" -> "404",
    ":status" -> "500",
    "accept-charset" -> EMPTY,
    "accept-encoding" -> EMPTY,
    "accept-language" -> EMPTY,
    "accept-ranges" -> EMPTY,
    "accept" -> EMPTY,
    "access-control-allow-origin" -> EMPTY,
    "age" -> EMPTY,
    "allow" -> EMPTY,
    "authorization" -> EMPTY,
    "cache-control" -> EMPTY,
    "content-disposition" -> EMPTY,
    "content-encoding" -> EMPTY,
    "content-language" -> EMPTY,
    "content-length" -> EMPTY,
    "content-location" -> EMPTY,
    "content-range" -> EMPTY,
    "content-type" -> EMPTY,
    "cookie" -> EMPTY,
    "date" -> EMPTY,
    "etag" -> EMPTY,
    "expect" -> EMPTY,
    "expires" -> EMPTY,
    "from" -> EMPTY,
    "host" -> EMPTY,
    "if-match" -> EMPTY,
    "if-modified-since" -> EMPTY, 
    "if-none-match" -> EMPTY,
    "if-range" -> EMPTY,
    "if-unmodified-since" -> EMPTY,
    "last-modified" -> EMPTY,
    "link" -> EMPTY,
    "location" -> EMPTY,
    "max-forwards" -> EMPTY, 
    "proxy-authenticate" -> EMPTY,
    "proxy-authorization" -> EMPTY,
    "range" -> EMPTY,
    "referer" -> EMPTY, 
    "refresh" -> EMPTY,
    "retry-after" -> EMPTY,
    "server" -> EMPTY,
    "set-cookie" -> EMPTY,
    "strict-transport-security" -> EMPTY,
    "transfer-encoding" -> EMPTY,
    "user-agent" -> EMPTY,
    "vary" -> EMPTY,
    "via" -> EMPTY,
    "www-authenticate" -> EMPTY
  )

  val size = value.size
}
/*
          +-------+-----------------------------+---------------+
          | Index | Header Name                 | Header Value  |
          +-------+-----------------------------+---------------+
          | 1     | :authority                  |               |
          | 2     | :method                     | GET           |
          | 3     | :method                     | POST          |
          | 4     | :path                       | /             |
          | 5     | :path                       | /index.html   |
          | 6     | :scheme                     | http          |
          | 7     | :scheme                     | https         |
          | 8     | :status                     | 200           |
          | 9     | :status                     | 204           |
          | 10    | :status                     | 206           |
          | 11    | :status                     | 304           |
          | 12    | :status                     | 400           |
          | 13    | :status                     | 404           |
          | 14    | :status                     | 500           |
          | 15    | accept-charset              |               |
          | 16    | accept-encoding             | gzip, deflate |
          | 17    | accept-language             |               |
          | 18    | accept-ranges               |               |
          | 19    | accept                      |               |
          | 20    | access-control-allow-origin |               |
          | 21    | age                         |               |
          | 22    | allow                       |               |
          | 23    | authorization               |               |
          | 24    | cache-control               |               |
          | 25    | content-disposition         |               |
          | 26    | content-encoding            |               |
          | 27    | content-language            |               |
          | 28    | content-length              |               |
          | 29    | content-location            |               |
          | 30    | content-range               |               |
          | 31    | content-type                |               |
          | 32    | cookie                      |               |
          | 33    | date                        |               |
          | 34    | etag                        |               |
          | 35    | expect                      |               |
          | 36    | expires                     |               |
          | 37    | from                        |               |
          | 38    | host                        |               |
          | 39    | if-match                    |               |
          | 40    | if-modified-since           |               |
          | 41    | if-none-match               |               |
          | 42    | if-range                    |               |
          | 43    | if-unmodified-since         |               |
          | 44    | last-modified               |               |
          | 45    | link                        |               |
          | 46    | location                    |               |
          | 47    | max-forwards                |               |
          | 48    | proxy-authenticate          |               |
          | 49    | proxy-authorization         |               |
          | 50    | range                       |               |
          | 51    | referer                     |               |
          | 52    | refresh                     |               |
          | 53    | retry-after                 |               |
          | 54    | server                      |               |
          | 55    | set-cookie                  |               |
          | 56    | strict-transport-security   |               |
          | 57    | transfer-encoding           |               |
          | 58    | user-agent                  |               |
          | 59    | vary                        |               |
          | 60    | via                         |               |
          | 61    | www-authenticate            |               |
          +-------+-----------------------------+---------------+
*/