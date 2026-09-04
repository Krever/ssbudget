package ssbudget.backend.analytics

import cats.data.OptionT
import cats.effect.IO
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.Location
import org.typelevel.ci.{CIString, CIStringSyntax}
import sttp.capabilities.fs2.Fs2Streams
import sttp.client3.{SttpBackend, asStreamAlwaysUnsafe, emptyRequest}
import sttp.model.{Method as SttpMethod, Uri as SttpUri}
import ssbudget.backend.AuthRoutes
import ssbudget.backend.auth.SessionService
import ssbudget.shared.api.AuthEndpoints

import scala.concurrent.duration.*

/** Serves Metabase under a path prefix on the app's own origin, gated by the app's session.
  *
  * The user authenticates once, to us, with the passkey session they already have; this then attaches the service account's Metabase session on the
  * way through. Metabase itself binds to loopback and is never reachable directly, so its login page cannot be brute-forced and there is no second
  * credential for anyone to manage.
  *
  * Serving it under a prefix rather than a second port works because Metabase honours the basename of `MB_SITE_URL`: told its site URL ends in
  * `/metabase`, it emits `<base href="/metabase/">` and its assets are relative, so everything resolves through the prefix. Metabase's own API routes
  * live below the prefix too, so they never collide with ours.
  */
object AnalyticsProxyRoutes {

  /** Headers that describe a single hop and must not be forwarded. */
  private val HopByHop: Set[CIString] = Set(
    ci"connection",
    ci"keep-alive",
    ci"proxy-authenticate",
    ci"proxy-authorization",
    ci"te",
    ci"trailer",
    ci"transfer-encoding",
    ci"upgrade",
    ci"host",
    ci"content-length",
  )

  private val SessionHeader = "X-Metabase-Session"
  private val AppRoot       = Uri(path = Uri.Path.Root)

  /** Response headers that stop here.
    *
    * `Set-Cookie`: Metabase issues its own session/device cookies, and swallowing them keeps exactly one source of truth for who the browser is,
    * which is the app's session. `Content-Encoding`: we asked upstream for plain bytes, so whatever it claims here is moot — the server's own
    * middleware compresses on the way out.
    */
  private val DroppedResponseHeaders = Set(SessionHeader, "set-cookie", "content-encoding")

  /** Metabase can take a while over a large question; this only bounds the wait for response headers. */
  private val UpstreamTimeout = 5.minutes

  def make(
      config: MetabaseConfig,
      client: MetabaseClient,
      backend: SttpBackend[IO, Fs2Streams[IO]],
      sessionService: SessionService,
      testMode: Boolean,
  ): HttpRoutes[IO] = {
    val prefix    = Uri.Path.unsafeFromString(config.path)
    val prefixLen = config.path.length
    val upstream  = SttpUri.unsafeParse(config.baseUrl)

    HttpRoutes[IO] { req =>
      if !req.uri.path.startsWith(prefix) then OptionT.none[IO, Response[IO]]
      else
        OptionT.liftF {
          val token = req.cookies.find(_.name == AuthEndpoints.SessionCookieName).map(_.content)
          AuthRoutes.validateSession(sessionService, token, testMode).flatMap {
            // Send an unauthenticated visitor to the app, which will show its own login.
            case Left(_)  => IO.pure(Response[IO](Status.Found).putHeaders(Location(AppRoot)))
            case Right(_) => forward(req, client, backend, upstream, prefixLen)
          }
        }
    }
  }

  private def forward(
      req: Request[IO],
      client: MetabaseClient,
      backend: SttpBackend[IO, Fs2Streams[IO]],
      upstream: SttpUri,
      prefixLen: Int,
  ): IO[Response[IO]] =
    client.sessionToken.flatMap {
      case Left(err)    => InternalServerError(s"Analytics unavailable: $err")
      case Right(token) =>
        val stripped = req.uri.path.toString.drop(prefixLen)
        val target   = upstream
          .withWholePath(if stripped.isEmpty then "/" else stripped)
          .withParams(req.uri.query.toList.map { case (k, v) => k -> v.getOrElse("") }*)

        val outgoing = emptyRequest
          .method(SttpMethod(req.method.name), target)
          .headers(forwardedHeaders(req.headers)*)
          .header(SessionHeader, token)
          // A proxy passes a redirect back to the browser: following it here would resolve it against
          // the loopback upstream and strip the path prefix from what the browser finally sees.
          .followRedirects(false)
          .readTimeout(UpstreamTimeout)
          .response(asStreamAlwaysUnsafe(Fs2Streams[IO]))

        val withBody =
          if hasBody(req) then outgoing.streamBody(Fs2Streams[IO])(req.body)
          else outgoing

        withBody.send(backend).map { resp =>
          Response[IO](
            status = Status.fromInt(resp.code.code).getOrElse(Status.BadGateway),
            headers = Headers(resp.headers.collect {
              case h if !HopByHop.contains(CIString(h.name)) && !DroppedResponseHeaders.exists(h.is) =>
                Header.Raw(CIString(h.name), h.value)
            }),
            // The body is a stream, so Metabase's multi-megabyte assets pass through without ever
            // being held in memory here.
            body = resp.body,
          )
        }
    }

  /** Only forward a body when the request actually carries one: a stream body on a GET makes the upstream expect content that never arrives. */
  private def hasBody(req: Request[IO]): Boolean =
    req.contentLength.exists(_ > 0) || req.isChunked

  /** The app's own cookies mean nothing to Metabase, and forwarding them would let a stale `metabase.SESSION` override the token we inject.
    *
    * `Accept-Encoding` goes too: the sttp backend transparently decompresses what it receives, so asking for gzip upstream would leave us serving
    * inflated bytes still labelled `Content-Encoding: gzip`. The hop to Metabase is loopback, and the hop to the browser is compressed by the
    * server's own middleware.
    */
  private def forwardedHeaders(headers: Headers): Seq[sttp.model.Header] =
    headers.headers.collect {
      case h if !HopByHop.contains(h.name) && h.name != ci"cookie" && h.name != ci"accept-encoding" =>
        sttp.model.Header(h.name.toString, h.value)
    }
}
