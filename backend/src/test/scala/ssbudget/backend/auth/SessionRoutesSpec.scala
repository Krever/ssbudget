package ssbudget.backend.auth

import cats.effect.IO
import doobie.implicits.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.*
import org.http4s.implicits.*
import ssbudget.backend.AuthRoutes
import ssbudget.backend.db.Repositories
import ssbudget.backend.db.repository.RepositorySpec
import ssbudget.shared.api.AuthEndpoints

import java.time.Instant

/** The session gate at the HTTP level: what a request without a session gets, how a session is stored, and which actions need a recent proof of
  * identity on top of the session.
  */
class SessionRoutesSpec extends RepositorySpec {

  private val password = "correct horse"

  /** The auth routes as production has them (no test mode), with a password set and one session open. */
  private def fixtures: IO[(Repositories, HttpApp[IO], String)] = {
    val repos     = Repositories.fromTransactor(xa)
    val passwords = PasswordService()
    val sessions  = SessionService(repos.sessions)
    for {
      webAuthn <- WebAuthnService(repos.passkeyCredentials, "localhost", "SSBudget", Set("http://localhost:8080"))
      hash     <- passwords.hash(password)
      _        <- repos.authConfig.upsert(hash)
      token    <- sessions.createSession()
      routes    =
        AuthRoutes.make(repos.authConfig, repos.passkeyCredentials, passwords, sessions, webAuthn, new McpTokenService(repos.mcpTokens))
    } yield (repos, routes.orNotFound, token)
  }

  private def withSession(req: Request[IO], token: String): Request[IO] = req.addCookie(AuthEndpoints.SessionCookieName, token)

  private val listPasskeys                                      = Request[IO](Method.GET, uri"/api/auth/passkeys")
  private val deletePasskey                                     = Request[IO](Method.DELETE, uri"/api/auth/passkeys/some-id")
  private def confirm(pass: String)                             =
    Request[IO](Method.POST, uri"/api/auth/confirm").withEntity(Json.obj("CurrentPassword" := Json.obj("password" := pass)))
  private def status(app: HttpApp[IO], token: String): IO[Json] =
    app.run(withSession(Request[IO](Method.GET, uri"/api/auth/status"), token)).flatMap(_.as[Json])

  /** Makes the proof of identity of every session old, as it is some time after the login. */
  private def ageConfirmation: IO[Unit] =
    sql"UPDATE sessions SET confirmed_at = ${Instant.now().minus(SessionService.ConfirmationWindow).minusSeconds(60).toString}".update.run
      .transact(xa)
      .void

  "a request with no session, or with a token that is not a session, gets 401" in {
    for {
      (_, app, token) <- fixtures
      none            <- app.run(listPasskeys)
      wrong           <- app.run(withSession(listPasskeys, "not-a-session"))
      ok              <- app.run(withSession(listPasskeys, token))
    } yield {
      none.status shouldBe Status.Unauthorized
      wrong.status shouldBe Status.Unauthorized
      ok.status shouldBe Status.Ok
    }
  }

  "the database holds the digest of a session token, never the token" in {
    for {
      (_, _, token) <- fixtures
      stored        <- sql"SELECT token FROM sessions".query[String].to[List].transact(xa)
    } yield {
      stored shouldBe List(SessionService.digest(token))
      stored.head should not be token
    }
  }

  "a passkey change needs a recent proof of identity: a login counts, and after some time only a new proof does" in {
    for {
      (_, app, token) <- fixtures
      fresh           <- app.run(withSession(deletePasskey, token))
      freshStatus     <- status(app, token)
      _               <- ageConfirmation
      stale           <- app.run(withSession(deletePasskey, token))
      staleBody       <- stale.as[String]
      staleStatus     <- status(app, token)
      stillIn         <- app.run(withSession(listPasskeys, token))
      wrongProof      <- app.run(withSession(confirm("nope"), token))
      afterWrong      <- app.run(withSession(deletePasskey, token))
      goodProof       <- app.run(withSession(confirm(password), token))
      afterGood       <- app.run(withSession(deletePasskey, token))
    } yield {
      fresh.status shouldBe Status.Ok
      freshStatus.hcursor.get[Boolean]("recentlyConfirmed") shouldBe Right(true)
      // The session is still good for all the rest; it only cannot do this.
      stale.status shouldBe Status.BadRequest
      staleBody shouldBe AuthEndpoints.NeedsConfirmation
      staleStatus.hcursor.get[Boolean]("recentlyConfirmed") shouldBe Right(false)
      staleStatus.hcursor.get[Boolean]("loggedIn") shouldBe Right(true)
      stillIn.status shouldBe Status.Ok
      wrongProof.status shouldBe Status.BadRequest
      afterWrong.status shouldBe Status.BadRequest
      goodProof.status shouldBe Status.Ok
      afterGood.status shouldBe Status.Ok
    }
  }
}
