package ssbudget.backend.auth

import cats.effect.IO
import ssbudget.backend.db.repository.{McpTokenRepository, McpTokenRow}
import ssbudget.backend.service.AppClock
import ssbudget.shared.api.{CreatedMcpToken, McpTokenInfo}

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.UUID

/** The credentials of the MCP endpoint: access tokens that the server makes and the user manages on the Settings page.
  *
  * The endpoint is a second door into the app, so it has its own keys and shares nothing with the browser login:
  *   - a session cookie does not open it, and a token does not open `/api`. A page in a browser thus cannot use the login of the user to call it;
  *   - a token opens only the categorization tools. It does not give balances, the database export or the reset;
  *   - with no token in the table, no request passes. Test mode does not change that.
  *
  * A token is 256 random bits. It is shown one time, when it is made. The database holds only its SHA-256 digest, so a copy of the database (a
  * backup, the export on the Settings page) does not give access. A plain digest is sufficient here: a slow password hash protects secrets that a
  * person chose, and these have full entropy. A token is found by its digest, so the comparison does not depend on how much of a wrong token is
  * right.
  */
final class McpTokenService(repo: McpTokenRepository, clock: AppClock = AppClock.system) {

  import McpTokenService.*

  /** Makes a token. The result holds the token itself; this is the only time that it exists outside the client. */
  def create(name: String): IO[Either[String, CreatedMcpToken]] = {
    val label = name.trim
    if label.length > MaxNameLength then IO.pure(Left(s"A token name must not be longer than $MaxNameLength characters"))
    else
      repo.count.flatMap { existing =>
        if existing >= MaxTokens then IO.pure(Left(s"There are $MaxTokens tokens already. Revoke one first."))
        else
          for {
            token <- SecureToken.generate(TokenBytes).map(TokenPrefix + _)
            now   <- clock.now
            row    = McpTokenRow(UUID.randomUUID().toString, if label.isEmpty then DefaultName else label, digest(token), now, None)
            _     <- repo.create(row)
          } yield Right(CreatedMcpToken(info(row), token))
      }
  }

  def list: IO[List[McpTokenInfo]] = repo.findAll.map(_.map(info))

  def revoke(id: String): IO[Unit] = repo.delete(id)

  /** The token that an `Authorization` header value holds, if it is `Bearer <a token that exists>`. The scheme is not case-sensitive (RFC 7235); the
    * token is. `lastUsedAt` is written at most one time a minute, so a busy session is not one database write per call.
    */
  def authenticate(authorizationHeader: String): IO[Option[McpTokenInfo]] = {
    val (scheme, rest) = authorizationHeader.span(_ != ' ')
    val token          = rest.trim
    // A text that does not have the form of a token is refused before the database is read.
    if !scheme.equalsIgnoreCase("Bearer") || !token.startsWith(TokenPrefix) || token.length > MaxTokenLength then IO.pure(None)
    else
      repo.findByHash(digest(token)).flatMap {
        case None      => IO.pure(None)
        case Some(row) =>
          clock.now.flatMap { now =>
            val stale = row.lastUsedAt.forall(_.plusSeconds(60).isBefore(now))
            IO.whenA(stale)(repo.updateLastUsed(row.id, now)).as(Some(info(row)))
          }
      }
  }
}

object McpTokenService {

  /** Marks the text as a token of this app, for the user and for secret scanners. */
  val TokenPrefix = "ssb_mcp_"

  val TokenBytes     = 32
  val MaxTokenLength = 128
  val MaxTokens      = 10
  val MaxNameLength  = 60
  val DefaultName    = "MCP token"

  private def digest(token: String): String =
    MessageDigest.getInstance("SHA-256").digest(token.getBytes(UTF_8)).map("%02x".format(_)).mkString

  private def info(row: McpTokenRow): McpTokenInfo = McpTokenInfo(row.id, row.name, row.createdAt, row.lastUsedAt)
}
