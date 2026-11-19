package ssbudget.backend

import cats.data.{Kleisli, OptionT}
import cats.effect.IO
import org.http4s.headers.`Content-Length`
import org.http4s.server.middleware.EntityLimiter
import org.http4s.{Header, HttpRoutes, Method, Request, Response, Status}
import org.typelevel.ci.CIString

/** The defences that every route of the app gets, in one place: what a request must look like to be accepted, and what each answer tells the browser
  * to refuse. They do not replace the session check of a route; they close what a session cookie alone leaves open.
  */
object Hardening {

  /** The largest body of an ordinary request. The API takes small JSON documents. */
  val defaultBodyLimit: Long = 2L * 1024 * 1024

  /** The largest database file that the import takes. */
  val importBodyLimit: Long = 512L * 1024 * 1024

  private val importPath = "/api/database/import"

  /** The answer headers of the app's own pages and API.
    *   - The content policy allows only what the app has: its own scripts, styles and images, and its own frames (the analytics page). Inline styles
    *     are allowed, because the UI sets some of them on elements; inline scripts are not.
    *   - `frame-ancestors` and `X-Frame-Options`: another site cannot put the app in a frame, so it cannot trick a click.
    *   - `nosniff`: the browser does not guess a type for an answer.
    *   - `Referrer-Policy`: no address of the app goes to another site.
    */
  val headers: List[Header.Raw] = List(
    "Content-Security-Policy" ->
      "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; frame-ancestors 'self'; base-uri 'self'; form-action 'self'; object-src 'none'",
    "X-Frame-Options"         -> "SAMEORIGIN",
    "X-Content-Type-Options"  -> "nosniff",
    "Referrer-Policy"         -> "same-origin",
  ).map { case (name, value) => Header.Raw(CIString(name), value) }

  /** Is this a request that another site started? A browser names the page that started a write in the `Origin` header. The request is the app's own
    * when that origin is the host the request was sent to, or one of the origins the app is configured for. A request with no `Origin` (a plain
    * navigation, a command-line client) is not a cross-site write.
    */
  def crossSiteWrite(request: Request[IO], allowedOrigins: Set[String]): Boolean = {
    val isWrite = !Set(Method.GET, Method.HEAD, Method.OPTIONS).contains(request.method)
    val origin  = request.headers.get(CIString("Origin")).map(_.head.value)
    val host    = request.headers.get(CIString("Host")).map(_.head.value)
    isWrite && origin.exists { o =>
      val authority = o.replaceFirst("^[a-zA-Z][a-zA-Z0-9+.-]*://", "")
      !host.contains(authority) && !allowedOrigins.map(_.trim).contains(o)
    }
  }

  private def refuse(status: Status, text: String): OptionT[IO, Response[IO]] =
    OptionT.pure[IO](Response[IO](status).withEntity(text))

  /** Refuse a write that another site started. For the routes that keep their own answer headers (the analytics proxy). */
  def sameSiteWrites(allowedOrigins: Set[String])(routes: HttpRoutes[IO]): HttpRoutes[IO] =
    Kleisli { request =>
      if crossSiteWrite(request, allowedOrigins) then refuse(Status.Forbidden, "A request from another site is not accepted")
      else routes(request)
    }

  /** All of it, for the app's own routes: same-site writes only, a limit on the size of a body, and the answer headers. */
  def apply(allowedOrigins: Set[String])(routes: HttpRoutes[IO]): HttpRoutes[IO] = {
    val limited = EntityLimiter.httpRoutes(routes, importBodyLimit)
    val checked = Kleisli { (request: Request[IO]) =>
      val limit    = if request.uri.path.renderString == importPath then importBodyLimit else defaultBodyLimit
      val declared = request.headers.get[`Content-Length`].map(_.length)
      if crossSiteWrite(request, allowedOrigins) then refuse(Status.Forbidden, "A request from another site is not accepted")
      else if declared.exists(_ > limit) then refuse(Status.PayloadTooLarge, "The request is too large")
      else limited(request)
    }
    checked.map(response => headers.foldLeft(response)((r, h) => r.putHeaders(h)))
  }
}
