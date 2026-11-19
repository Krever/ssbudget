package ssbudget.backend.mcp

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.*
import org.http4s.implicits.*
import ssbudget.backend.AuthRoutes
import ssbudget.backend.auth.{McpTokenService, PasswordService, SessionService, WebAuthnService}
import ssbudget.backend.db.Repositories
import ssbudget.backend.db.repository.RepositorySpec
import ssbudget.shared.api.AuthEndpoints

/** The Settings-page side of MCP tokens: who can make, list and revoke them. */
class McpTokenRoutesSpec extends RepositorySpec {

  private val password = "correct horse"

  /** The auth routes as production has them (no test mode), with a password set and one session open. */
  private def fixtures(testMode: Boolean = false): IO[(Repositories, HttpApp[IO], String)] = {
    val repos     = Repositories.fromTransactor(xa)
    val passwords = PasswordService()
    val sessions  = SessionService(repos.sessions)
    for {
      webAuthn <- WebAuthnService(repos.passkeyCredentials, "localhost", "SSBudget", Set("http://localhost:8080"))
      hash     <- passwords.hash(password)
      _        <- repos.authConfig.upsert(hash)
      session  <- sessions.createSession()
      routes    =
        AuthRoutes.make(repos.authConfig, repos.passkeyCredentials, passwords, sessions, webAuthn, new McpTokenService(repos.mcpTokens), testMode)
    } yield (repos, routes.orNotFound, session)
  }

  private def withSession(req: Request[IO], session: String): Request[IO] = req.addCookie(AuthEndpoints.SessionCookieName, session)

  private def create(name: String, pass: String): Request[IO] =
    Request[IO](Method.POST, uri"/api/auth/mcp-tokens").withEntity(
      Json.obj("name" := name, "proof" := Json.obj("CurrentPassword" := Json.obj("password" := pass))),
    )

  "making a token needs the session AND a proof of identity" in {
    for {
      (repos, app, session) <- fixtures()
      noSession             <- app.run(create("a", password))
      wrongPassword         <- app.run(withSession(create("a", "nope"), session))
      afterRefused          <- repos.mcpTokens.count
      ok                    <- app.run(withSession(create("laptop", password), session))
      body                  <- ok.as[Json]
      rows                  <- repos.mcpTokens.findAll
    } yield {
      noSession.status.isSuccess shouldBe false
      wrongPassword.status.isSuccess shouldBe false
      afterRefused shouldBe 0
      ok.status shouldBe Status.Ok
      body.hcursor.get[String]("token").toOption.get should startWith(McpTokenService.TokenPrefix)
      body.hcursor.downField("info").get[String]("name") shouldBe Right("laptop")
      rows.map(_.name) shouldBe List("laptop")
    }
  }

  "test mode opens the session check, not the proof of identity" in {
    for {
      (repos, app, _) <- fixtures(testMode = true)
      wrong           <- app.run(create("a", "nope"))
      count           <- repos.mcpTokens.count
    } yield {
      wrong.status.isSuccess shouldBe false
      count shouldBe 0
    }
  }

  "the list never holds a token or its digest, and a revoked token is gone" in {
    for {
      (repos, app, session) <- fixtures()
      made                  <- app.run(withSession(create("laptop", password), session)).flatMap(_.as[Json])
      token                  = made.hcursor.get[String]("token").toOption.get
      id                     = made.hcursor.downField("info").get[String]("id").toOption.get
      hash                  <- repos.mcpTokens.findAll.map(_.head.tokenHash)
      noSession             <- app.run(Request[IO](Method.GET, uri"/api/auth/mcp-tokens"))
      list                  <- app.run(withSession(Request[IO](Method.GET, uri"/api/auth/mcp-tokens"), session)).flatMap(_.as[String])
      revokeNoSession       <- app.run(Request[IO](Method.DELETE, uri"/api/auth/mcp-tokens" / id))
      stillThere            <- repos.mcpTokens.count
      revoke                <- app.run(withSession(Request[IO](Method.DELETE, uri"/api/auth/mcp-tokens" / id), session))
      gone                  <- repos.mcpTokens.count
    } yield {
      noSession.status.isSuccess shouldBe false
      list should include("laptop")
      list should not include token
      list should not include hash
      revokeNoSession.status.isSuccess shouldBe false
      stillThere shouldBe 1
      revoke.status shouldBe Status.Ok
      gone shouldBe 0
    }
  }
}
