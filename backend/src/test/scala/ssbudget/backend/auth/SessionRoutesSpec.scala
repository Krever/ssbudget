package ssbudget.backend.auth

import cats.effect.{IO, Ref}
import doobie.implicits.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.*
import org.http4s.implicits.*
import ssbudget.backend.AuthRoutes
import ssbudget.backend.db.Repositories
import ssbudget.backend.db.repository.RepositorySpec
import ssbudget.backend.service.AppClock
import ssbudget.shared.api.{ApiError, AuthEndpoints}

import java.time.Instant

/** The session gate at the HTTP level: what a request without a session gets, how a session is stored, and which actions need a recent proof of
  * identity on top of the session.
  */
class SessionRoutesSpec extends RepositorySpec {

  private val password = "correct horse"

  /** The auth routes as production has them (no test mode), with a password set and one session open. */
  private def fixtures: IO[(Repositories, HttpApp[IO], String)] = fixturesAt.map { case (repos, app, token, _) => (repos, app, token) }

  /** As `fixtures`, with the clock of the sessions: a test moves it forward with `update`. */
  private def fixturesAt: IO[(Repositories, HttpApp[IO], String, Ref[IO, Instant])] = {
    val repos     = Repositories.fromTransactor(xa)
    val passwords = PasswordService()
    for {
      time     <- Ref.of[IO, Instant](Instant.parse("2026-03-01T10:00:00Z"))
      sessions  = SessionService(repos.sessions, AppClock(time.get))
      webAuthn <- WebAuthnService(repos.passkeyCredentials, "localhost", "SSBudget", Set("http://localhost:8080"))
      hash     <- passwords.hash(password)
      _        <- repos.authConfig.upsert(hash)
      token    <- sessions.createSession()
      routes    =
        AuthRoutes.make(repos.authConfig, repos.passkeyCredentials, passwords, sessions, webAuthn, new McpTokenService(repos.mcpTokens))
    } yield (repos, routes.orNotFound, token, time)
  }

  private def withSession(req: Request[IO], token: String): Request[IO] = req.addCookie(AuthEndpoints.SessionCookieName, token)

  private val listPasskeys                                      = Request[IO](Method.GET, uri"/api/auth/passkeys")
  private val deletePasskey                                     = Request[IO](Method.DELETE, uri"/api/auth/passkeys/some-id")
  private def confirm(pass: String)                             =
    Request[IO](Method.POST, uri"/api/auth/confirm").withEntity(Json.obj("CurrentPassword" := Json.obj("password" := pass)))
  private def status(app: HttpApp[IO], token: String): IO[Json] =
    app.run(withSession(Request[IO](Method.GET, uri"/api/auth/status"), token)).flatMap(_.as[Json])

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
      (_, app, token, time) <- fixturesAt
      fresh                 <- app.run(withSession(deletePasskey, token))
      freshStatus           <- status(app, token)
      // One minute before the end of the window the proof is still good; one minute after, it is not.
      _                     <- time.update(_.plus(SessionService.ConfirmationWindow).minusSeconds(60))
      nearEnd               <- app.run(withSession(deletePasskey, token))
      _                     <- time.update(_.plusSeconds(120))
      stale                 <- app.run(withSession(deletePasskey, token))
      staleBody             <- stale.as[String]
      staleStatus           <- status(app, token)
      stillIn               <- app.run(withSession(listPasskeys, token))
      wrongProof            <- app.run(withSession(confirm("nope"), token))
      afterWrong            <- app.run(withSession(deletePasskey, token))
      goodProof             <- app.run(withSession(confirm(password), token))
      afterGood             <- app.run(withSession(deletePasskey, token))
    } yield {
      fresh.status shouldBe Status.Ok
      nearEnd.status shouldBe Status.Ok
      freshStatus.hcursor.get[Boolean]("recentlyConfirmed") shouldBe Right(true)
      // The session is still good for all the rest; it only cannot do this.
      // Its own status, so that the page can tell it from a refused input.
      stale.status shouldBe Status.Forbidden
      staleBody shouldBe ApiError.needsConfirmation.message
      staleStatus.hcursor.get[Boolean]("recentlyConfirmed") shouldBe Right(false)
      staleStatus.hcursor.get[Boolean]("loggedIn") shouldBe Right(true)
      stillIn.status shouldBe Status.Ok
      wrongProof.status shouldBe Status.BadRequest
      afterWrong.status shouldBe Status.Forbidden
      goodProof.status shouldBe Status.Ok
      afterGood.status shouldBe Status.Ok
    }
  }

  "a session lives 30 days, and then it is not a login any more" in {
    for {
      (_, app, token, time) <- fixturesAt
      _                     <- time.update(_.plus(java.time.Duration.ofDays(30)).minusSeconds(60))
      lastDay               <- app.run(withSession(listPasskeys, token))
      _                     <- time.update(_.plusSeconds(120))
      after                 <- app.run(withSession(listPasskeys, token))
    } yield {
      lastDay.status shouldBe Status.Ok
      after.status shouldBe Status.Unauthorized
    }
  }
}
