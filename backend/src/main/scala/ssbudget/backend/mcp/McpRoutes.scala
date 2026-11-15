package ssbudget.backend.mcp

import cats.data.OptionT
import cats.effect.{IO, Ref}
import cats.implicits.*
import io.circe.Json
import org.http4s.*
import org.http4s.circe.*
import org.http4s.headers.{Allow, `Cache-Control`, `WWW-Authenticate`}
import org.slf4j.LoggerFactory
import org.typelevel.ci.*
import ssbudget.backend.auth.McpTokenService

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant

/** The HTTP side of the MCP endpoint (`POST /mcp`, the Streamable HTTP transport without the optional event stream).
  *
  * Each request passes these checks, in this order, before [[McpServer]] sees it:
  *   1. a bearer token that [[McpTokenService]] knows. Without it the answer is 401 and nothing else: not the method check, not a parse of the body;
  *   1. no `Origin` header. Browsers put one on each cross-site request, and no MCP client is a web page, so a request that has one is refused. This
  *      is the DNS-rebinding guard that the MCP specification requires;
  *   1. the method is POST;
  *   1. the body is not larger than [[MaxBodyBytes]].
  *
  * The token is never written to the log. Cookies are never read.
  */
object McpRoutes {

  val Path = "/mcp"

  /** A categorization call is some hundred bytes; the largest (a batch of 100 assignments) is below 20 kB. */
  val MaxBodyBytes = 256 * 1024

  private val log = LoggerFactory.getLogger("ssbudget.backend.mcp.McpRoutes")

  private val noStore = `Cache-Control`(CacheDirective.`no-store`)

  def make(auth: McpTokenService, server: McpServer): HttpRoutes[IO] = {
    val lastFailureLog = Ref.unsafe[IO, Instant](Instant.EPOCH)

    HttpRoutes[IO] { req =>
      val path = req.uri.path.renderString
      if path != Path && path != Path + "/" then OptionT.none
      else OptionT.liftF(handle(auth, server, lastFailureLog)(req).map(_.putHeaders(noStore)))
    }
  }

  private def handle(auth: McpTokenService, server: McpServer, lastFailureLog: Ref[IO, Instant])(req: Request[IO]): IO[Response[IO]] = {
    // Two Authorization headers are ambiguous, and an ambiguous credential is not a credential.
    val presented = req.headers.get(ci"Authorization").filter(_.tail.isEmpty).map(_.head.value)

    presented.flatTraverse(auth.authenticate).flatMap {
      case None        =>
        logFailure(req, lastFailureLog).as(
          Response[IO](Status.Unauthorized).putHeaders(`WWW-Authenticate`(Challenge("Bearer", "ssbudget-mcp"))),
        )
      case Some(token) => authenticated(server, token.name)(req)
    }
  }

  private def authenticated(server: McpServer, tokenName: String)(req: Request[IO]): IO[Response[IO]] = {
    if req.headers.get(ci"Origin").isDefined then IO.pure(plain(Status.Forbidden, "Requests from a browser are not accepted"))
    else if req.method != Method.POST then IO.pure(Response[IO](Status.MethodNotAllowed).putHeaders(Allow(Method.POST)))
    else
      // One byte more than the limit is read, so that "too large" is known and the rest of the body is never held in memory.
      req.body.take(MaxBodyBytes.toLong + 1).compile.to(Array).flatMap { bytes =>
        if bytes.length > MaxBodyBytes then IO.pure(plain(Status.PayloadTooLarge, "Request body is too large"))
        else
          io.circe.parser.parse(new String(bytes, UTF_8)).map(_.asObject) match {
            case Left(_)              => IO.pure(rpcError(McpServer.ParseError, "The body is not JSON"))
            // JSON-RPC batches were removed from MCP in revision 2025-06-18, and no client needs them.
            case Right(None)          => IO.pure(rpcError(McpServer.InvalidRequest, "A message must be one JSON object"))
            case Right(Some(message)) =>
              server.handle(message, tokenName).map {
                case Some(answer) => Response[IO](Status.Ok).withEntity(answer)
                case None         => Response[IO](Status.Accepted)
              }
          }
      }
  }

  private def plain(status: Status, message: String): Response[IO] = Response[IO](status).withEntity(message)

  private def rpcError(code: Int, message: String): Response[IO] =
    Response[IO](Status.BadRequest).withEntity(McpServer.error(Json.Null, code, message))

  /** Records a refused request, at most one line a minute: a flood of wrong tokens must not become a flood of log lines. The address is the one that
    * fly.io reports; the Authorization header itself is never logged.
    */
  private def logFailure(req: Request[IO], lastFailureLog: Ref[IO, Instant]): IO[Unit] =
    for {
      now <- IO.realTimeInstant
      due <- lastFailureLog.modify(last => if last.plusSeconds(60).isAfter(now) then (last, false) else (now, true))
      _   <- IO.whenA(due) {
               // The header is text from the network: only the characters of an IP address go to the log.
               val from = req.headers
                 .get(ci"Fly-Client-IP")
                 .map(_.head.value.filter(c => c.isLetterOrDigit || c == '.' || c == ':').take(45))
                 .orElse(req.remoteAddr.map(_.toString))
                 .getOrElse("unknown")
               IO(log.warn(s"[mcp] refused a request without a valid token, from $from"))
             }
    } yield ()
}
