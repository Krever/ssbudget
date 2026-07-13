package ssbudget.backend

import cats.effect.{IO, Resource}
import cats.effect.std.Supervisor
import cats.implicits.*
import com.comcast.ip4s.{Host, Port, host}
import doobie.hikari.HikariTransactor
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Server
import sttp.client3.httpclient.cats.HttpClientCatsBackend
import sttp.tapir.server.http4s.Http4sServerInterpreter

import ssbudget.backend.auth.{PasswordService, SessionService, WebAuthnService}
import ssbudget.backend.banking.{
  BankingService,
  EnableBankingClient,
  EnableBankingConfig,
  EnableBankingJwt,
  ImportJobService,
  RuleEngineService,
  TransactionImportService,
}
import ssbudget.backend.db.Repositories
import ssbudget.backend.service.CurrencyService
import ssbudget.shared.api.HealthEndpoint

/** Reusable server builder for production and testing */
object ServerBuilder {

  private val healthRoute = Http4sServerInterpreter[IO]().toRoutes(
    HealthEndpoint.health.serverLogicSuccess(_ => IO.pure("ok")),
  )

  // WebAuthn configuration from environment
  private def defaultRpId: String           = sys.env.getOrElse("SSBUDGET_RP_ID", "localhost")
  private def defaultRpName: String         = sys.env.getOrElse("SSBUDGET_RP_NAME", "SSBudget")
  private def defaultRpOrigins: Set[String] = sys.env
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
      webAuthnOrigins: Option[Set[String]] = None,
  ): Resource[IO, Server] = {
    val rpOrigins = webAuthnOrigins.getOrElse(defaultRpOrigins)
    for {
      sttpBackend     <- HttpClientCatsBackend.resource[IO]()
      webAuthnService <- Resource.eval(WebAuthnService(repos.passkeyCredentials, defaultRpId, defaultRpName, rpOrigins))
      ebConfigOpt     <- Resource.eval(EnableBankingConfig.fromEnv)
      ebClientOpt     <- ebConfigOpt.traverse { cfg =>
                           Resource.eval(EnableBankingJwt.create(cfg).map(jwt => new EnableBankingClient(cfg, jwt, sttpBackend)))
                         }
      _               <- Resource.eval(
                           IO.println(if ebClientOpt.isDefined then "Enable Banking integration: configured" else "Enable Banking integration: not configured"),
                         )
      // Re-apply the built-in transaction rules (internal-transfer detection) then the user-defined categorization rules on every boot,
      // so rule tweaks take effect on restart without needing a re-import.
      _               <- Resource.eval(repos.bankTransactions.markInternalTransfers())
      ruleEngine       = new RuleEngineService(repos)
      _               <- Resource.eval(ruleEngine.applyRules())
      // Background-job runner lives on an app-scoped supervisor so import/sync work outlives the HTTP request.
      supervisor      <- Supervisor[IO]
      // Any job left Running by a previous process was interrupted by the restart — mark it Failed so the UI doesn't show a phantom in-progress run.
      _               <- Resource.eval(IO.realTimeInstant.flatMap(now => repos.importJobs.failRunning(now, "Interrupted by a server restart")))
      server          <- {
        val passwordService  = PasswordService()
        val sessionService   = SessionService(repos.sessions)
        val currencyService  = new CurrencyService(repos, sttpBackend)
        val bankingService   = new BankingService(repos, ebClientOpt)
        val importService    = new TransactionImportService(repos, ebClientOpt, ruleEngine)
        val importJobService = new ImportJobService(repos, supervisor, bankingService, importService)

        val authRoutes = AuthRoutes.make(
          repos.authConfig,
          repos.passkeyCredentials,
          passwordService,
          sessionService,
          webAuthnService,
          testMode,
        )

        // Routes now handle their own auth via Tapir's serverSecurityLogic
        val dataRoutes =
          Routes.make(repos, xa, dbPath, sessionService, currencyService, bankingService, importService, importJobService, ruleEngine, testMode)

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
