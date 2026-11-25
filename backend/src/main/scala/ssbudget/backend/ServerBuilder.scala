package ssbudget.backend

import cats.effect.{IO, Resource}
import cats.effect.std.Supervisor
import cats.implicits.*
import com.comcast.ip4s.{Port, host}
import doobie.hikari.HikariTransactor
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Server
import sttp.client3.httpclient.fs2.HttpClientFs2Backend
import sttp.tapir.server.http4s.Http4sServerInterpreter

import ssbudget.backend.analytics.Analytics
import ssbudget.backend.auth.{McpTokenService, PasswordService, SessionService, WebAuthnService}
import ssbudget.backend.banking.{
  BankingService,
  CategorizationService,
  EnableBankingClient,
  EnableBankingConfig,
  EnableBankingJwt,
  ImportJobService,
  RuleEngineService,
  TransactionImportService,
}
import ssbudget.backend.db.Repositories
import ssbudget.backend.mcp.{CategorizationGuide, CategorizationTools, McpRoutes, McpServer}
import ssbudget.backend.service.{BudgetSnapshotService, BudgetSnapshotTrigger, CurrencyService}
import ssbudget.shared.api.HealthEndpoint

/** Reusable server builder for production and testing */
object ServerBuilder {

  /** sttp decodes `Content-Encoding` itself, and its default handler knows only gzip and deflate — anything else fails the response stream.
    * `identity` means "not encoded at all" (Metabase labels its static assets with it), so let those bytes through untouched.
    */
  private val passThroughIdentity: PartialFunction[(fs2.Stream[IO, Byte], String), fs2.Stream[IO, Byte]] = {
    case (body, encoding) if encoding.isEmpty || encoding.equalsIgnoreCase("identity") => body
  }

  /** "ok", and the commit the image was built from when the build left a `VERSION` file beside the app (see build.sh). A check that only needs "is it
    * up" reads the status; a person reads which build is live.
    */
  private val healthText: String = {
    val file = java.nio.file.Paths.get(sys.env.getOrElse("SSBUDGET_VERSION_FILE", "VERSION"))
    scala.util.Try(java.nio.file.Files.readString(file).trim).toOption.filter(_.nonEmpty).fold("ok")(version => s"ok $version")
  }

  private val healthRoute = Http4sServerInterpreter[IO]().toRoutes(
    HealthEndpoint.health.serverLogicSuccess(_ => IO.pure(healthText)),
  )

  // WebAuthn configuration from environment
  private def defaultRpId: String           = sys.env.getOrElse("SSBUDGET_RP_ID", "localhost")
  private def defaultRpName: String         = sys.env.getOrElse("SSBUDGET_RP_NAME", "SSBudget")
  private def defaultRpOrigins: Set[String] = sys.env
    .get("SSBUDGET_RP_ORIGINS")
    .map(_.split(",").toSet)
    .getOrElse(Set("http://localhost:3000", "http://localhost:8080"))

  /** On a deployment that is served over https, tell the browser to use https only from now on. Without it, the first request of a visit can go out
    * over plain http before the redirect.
    */
  private def withTransportSecurity(app: org.http4s.HttpApp[IO]): org.http4s.HttpApp[IO] =
    if defaultRpOrigins.forall(_.trim.startsWith("https://")) then {
      app.map(_.putHeaders(org.http4s.Header.Raw(org.typelevel.ci.CIString("Strict-Transport-Security"), "max-age=31536000")))
    } else {
      app
    }

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
      sttpBackend     <- HttpClientFs2Backend.resource[IO](customEncodingHandler = passThroughIdentity)
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
      sessionService   = SessionService(repos.sessions)
      // Sessions that have ended are of no use, and each one is a token in every backup. They go at each start and at each login.
      _               <- Resource.eval(sessionService.cleanupExpiredSessions().void)
      // Analytics is optional: without SSBUDGET_METABASE_URL this bundle is inert and the app is unchanged.
      analytics       <- Analytics.resource(dbPath, sttpBackend, repos.analyticsState, supervisor, sessionService, testMode)
      // Any job left Running by a previous process was interrupted by the restart — mark it Failed so the UI doesn't show a phantom in-progress run.
      _               <- Resource.eval(IO.realTimeInstant.flatMap(now => repos.importJobs.failRunning(now, "Interrupted by a server restart")))
      // Records the day's free-money components off ordinary traffic, and fills in whatever the suspended days missed.
      snapshotting    <- Resource.eval(BudgetSnapshotTrigger.wrap(new BudgetSnapshotService(repos), supervisor))
      server          <- {
        val passwordService  = PasswordService()
        val currencyService  = new CurrencyService(repos, sttpBackend)
        val bankingService   = new BankingService(repos, ebClientOpt)
        val importService    = new TransactionImportService(repos, ebClientOpt, ruleEngine)
        val importJobService =
          new ImportJobService(repos, supervisor, bankingService, importService, currencyService.refreshRates().map(_.void.left.map(_.message)))

        val mcpTokens      = new McpTokenService(repos.mcpTokens)
        val categorization = new CategorizationService(repos, ruleEngine)

        val authRoutes = AuthRoutes.make(
          repos.authConfig,
          repos.passkeyCredentials,
          passwordService,
          sessionService,
          webAuthnService,
          mcpTokens,
          testMode,
        )

        // Routes now handle their own auth via Tapir's serverSecurityLogic
        val dataRoutes =
          Routes.make(
            repos,
            xa,
            dbPath,
            sessionService,
            currencyService,
            bankingService,
            importJobService,
            ruleEngine,
            categorization,
            analytics.service,
            testMode,
          )

        // The MCP endpoint is always there, and closed: a request passes only with a token that was made on the Settings page.
        // `testMode` has no effect on it.
        val mcpRoutes = {
          val tools = new CategorizationTools(repos, categorization)
          McpRoutes.make(mcpTokens, new McpServer(tools.all, List(CategorizationGuide.prompt), CategorizationGuide.instructions))
        }

        // Static file routes for production (serves frontend build)
        val staticRoutes = StaticRoutes.make(staticDir)

        // Static routes answer only GET/HEAD on non-API paths (see StaticRoutes.make), so they can go before the API routes.
        // MCP goes before the static routes: their SPA fallback would answer `GET /mcp` with index.html.
        // The app's own routes get all the defences of [[Hardening]]. The analytics proxy keeps the answer headers of Metabase, and takes only
        // the same-site rule. MCP has its own rules (it refuses every request that names an origin).
        val ownRoutes = Hardening(defaultRpOrigins)(staticRoutes <+> healthRoute <+> authRoutes <+> snapshotting(dataRoutes))
        val allRoutes = mcpRoutes <+> Hardening.sameSiteWrites(defaultRpOrigins)(analytics.routes) <+> ownRoutes

        EmberServerBuilder
          .default[IO]
          .withHost(host"0.0.0.0")
          .withPort(port)
          .withHttpApp(withTransportSecurity(allRoutes.orNotFound))
          .build
      }
    } yield server
  }
}
