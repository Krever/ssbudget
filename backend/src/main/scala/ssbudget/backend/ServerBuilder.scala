package ssbudget.backend

import cats.effect.{IO, Resource}
import cats.implicits.*
import com.comcast.ip4s.{Host, Port, host}
import doobie.hikari.HikariTransactor
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Server
import sttp.client3.httpclient.cats.HttpClientCatsBackend
import sttp.tapir.server.http4s.Http4sServerInterpreter

import ssbudget.backend.auth.{PasswordService, SessionService, WebAuthnService}
import ssbudget.backend.db.Repositories
import ssbudget.backend.service.CurrencyService
import ssbudget.shared.api.HealthEndpoint

/** Reusable server builder for production and testing */
object ServerBuilder {

  private val healthRoute = Http4sServerInterpreter[IO]().toRoutes(
    HealthEndpoint.health.serverLogicSuccess(_ => IO.pure("ok")),
  )

  // WebAuthn configuration from environment
  private val rpId      = sys.env.getOrElse("SSBUDGET_RP_ID", "localhost")
  private val rpName    = sys.env.getOrElse("SSBUDGET_RP_NAME", "SSBudget")
  private val rpOrigins = sys.env
    .get("SSBUDGET_RP_ORIGINS")
    .map(_.split(",").toSet)
    .getOrElse(Set("http://localhost:3000", "http://localhost:8080"))

  // Static files directory (for production deployment)
  private val staticDir = sys.env.get("SSBUDGET_STATIC_DIR")

  /** Build a server resource with the given configuration */
  def build(
      repos: Repositories,
      xa: HikariTransactor[IO],
      port: Port,
      testMode: Boolean = false,
      dbPath: String = "data/ssbudget.db",
  ): Resource[IO, Server] = {
    for {
      sttpBackend     <- HttpClientCatsBackend.resource[IO]()
      webAuthnService <- Resource.eval(WebAuthnService(repos.passkeyCredentials, rpId, rpName, rpOrigins))
      server          <- {
        val passwordService = PasswordService()
        val sessionService  = SessionService(repos.sessions)
        val currencyService = new CurrencyService(repos, sttpBackend)

        val authRoutes = AuthRoutes.make(
          repos.authConfig,
          repos.passkeyCredentials,
          passwordService,
          sessionService,
          webAuthnService,
          testMode,
        )

        // Routes now handle their own auth via Tapir's serverSecurityLogic
        val dataRoutes = Routes.make(repos, xa, dbPath, sessionService, currencyService, testMode)

        // Static file routes for production (serves frontend build)
        val staticRoutes = StaticRoutes.make(staticDir)

        // Static routes first for non-API paths, then API routes
        // (staticRoutes only handles non-API paths via the make method)
        val allRoutes = staticRoutes <+> healthRoute <+> authRoutes <+> dataRoutes

        EmberServerBuilder
          .default[IO]
          .withHost(host"0.0.0.0")
          .withPort(port)
          .withHttpApp(allRoutes.orNotFound)
          .build
      }
    } yield server
  }
}
