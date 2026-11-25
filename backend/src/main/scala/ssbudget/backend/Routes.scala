package ssbudget.backend

import cats.effect.IO
import cats.implicits.*
import doobie.hikari.HikariTransactor
import org.http4s.HttpRoutes
import sttp.tapir.*
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.http4s.Http4sServerInterpreter
import ssbudget.backend.analytics.AnalyticsService
import ssbudget.backend.auth.SessionService
import ssbudget.backend.banking.{BankingService, CategorizationService, ImportJobService, RuleChange, RuleEngineService}
import ssbudget.backend.db.Repositories
import ssbudget.backend.service.*
import ssbudget.shared.api.*
import ssbudget.shared.model.*

/** How the refusal of a handler becomes an [[ApiError]]: a text is an "invalid" error, and an [[ApiError]] is itself. */
trait AsApiError[E] {
  def apply(refusal: E): ApiError
}

object AsApiError {
  given AsApiError[String]   = ApiError.invalid(_)
  given AsApiError[ApiError] = identity(_)
}

object Routes {

  private val log = org.slf4j.LoggerFactory.getLogger("ssbudget.routes")

  def make(
      repos: Repositories,
      xa: HikariTransactor[IO],
      dbPath: String,
      sessionService: SessionService,
      currencyService: CurrencyService,
      bankingService: BankingService,
      importJobService: ImportJobService,
      ruleEngine: RuleEngineService,
      categorization: CategorizationService,
      analyticsService: AnalyticsService,
      testMode: Boolean = false,
      clock: AppClock = AppClock.system,
  ): HttpRoutes[IO] = {
    val interpreter       = Http4sServerInterpreter[IO]()
    val categorySummaries = new CategorySummaryService(repos, clock)
    val budgetSnapshots   = new BudgetSnapshotService(repos, clock)
    val periods           = new PeriodService(repos, clock)
    val accounts          = new AccountService(repos, clock)
    val categories        = new CategoryService(repos, categorySummaries, ruleEngine, clock)
    val transactions      = new TransactionQueryService(repos)
    val backup            = new BackupService(xa, dbPath)

    def validateSession(tokenOpt: Option[String]): IO[Either[ApiError, Unit]] =
      AuthRoutes.validateSession(sessionService, tokenOpt, testMode)

    /** The answer of a handler as the answer of the endpoint. A handler refuses with a text (an "invalid" error) or with an [[ApiError]] that names
      * its kind. A handler that fails with an exception gives an error of the kind "failed" (status 500) with a text, and the cause goes to the log:
      * before, such a request got a 500 with an empty body, and the page could say nothing.
      */
    def answer[E: AsApiError, O](result: IO[Either[E, O]]): IO[Either[ApiError, O]] =
      result.map(_.left.map(summon[AsApiError[E]].apply)).handleErrorWith { cause =>
        IO(log.error("A request failed", cause))
          .as(Left(ApiError.failed(s"The server failed: ${Option(cause.getMessage).getOrElse(cause.getClass.getSimpleName)}")))
      }

    def route[I, O, E: AsApiError](ep: Endpoint[Option[String], I, ApiError, O, Any])(h: I => IO[Either[E, O]]): ServerEndpoint[Any, IO] =
      ep.serverSecurityLogic(validateSession).serverLogic(_ => i => answer(h(i)))

    /** A route whose handler cannot refuse: it only reads. */
    def read[I, O](ep: Endpoint[Option[String], I, ApiError, O, Any])(h: I => IO[O]): ServerEndpoint[Any, IO] =
      route(ep)(i => h(i).map(Right[ApiError, O](_)))

    /** A route that needs a recent proof of identity too (see `AuthRoutes.validateConfirmed`). */
    def confirmedRoute[I, O, E: AsApiError](ep: Endpoint[Option[String], I, ApiError, O, Any])(h: I => IO[Either[E, O]]): ServerEndpoint[Any, IO] =
      ep.serverSecurityLogic(token => AuthRoutes.validateConfirmed(sessionService, token, testMode)).serverLogic(_ => i => answer(h(i)))

    val routes = List(
      // Accounts (spending + savings, unified)
      read(Endpoints.accounts.list)(_ => repos.accounts.findAll),
      route(Endpoints.accounts.create)(accounts.create),
      route(Endpoints.accounts.update) { case (id, dto) => accounts.update(id, dto) },
      route(Endpoints.accounts.updateBalance) { case (id, dto) => accounts.updateBalance(id, dto) },
      route(Endpoints.accounts.delete)(accounts.delete),
      // Periods
      read(Endpoints.periods.list)(_ => repos.periods.findAll),
      route(Endpoints.periods.startNew)(asOf => periods.startNew(asOf)),
      route(Endpoints.periods.setExpectedEnd)((id, req) => periods.setExpectedEnd(id, req.expectedEnd)),
      route(Endpoints.periods.setPlannedSaving)((id, req) => periods.setPlannedSaving(id, req.plannedSavingCents)),
      read(Endpoints.periods.summaries)(limit => periods.summaries(limit)),
      read(Endpoints.periods.freeMoneyHistory)(_ => budgetSnapshots.history),
      // Savings
      read(Endpoints.savings.periodBaselines)(_ => periods.baselines),
      // Exchange rates (all rates to primary currency)
      read(Endpoints.exchangeRates.getAll)(_ => currencyService.latestRates),
      read(Endpoints.warnings.list)(_ => DataHealth.warnings(repos)),
      // Currency settings
      read(Endpoints.currencies.getSettings)(_ => currencyService.getSettings()),
      route(Endpoints.currencies.enable)(dto => currencyService.enableCurrency(dto.code)),
      route(Endpoints.currencies.disable)(code => currencyService.disableCurrency(code)),
      route(Endpoints.currencies.setPrimary)(dto => currencyService.setPrimaryCurrency(dto.code)),
      route(Endpoints.currencies.refreshRates)(_ => currencyService.refreshRates()),
      // Banking (Enable Banking integration)
      route(Endpoints.banking.listAspsps)(country => bankingService.listAspsps(country)),
      route(Endpoints.banking.connect)(req => bankingService.connect(req)),
      route(Endpoints.banking.callback)(req => bankingService.callback(req)),
      read(Endpoints.banking.connections)(_ => bankingService.listConnections),
      route(Endpoints.banking.disconnect)(id => bankingService.disconnect(id)),
      route(Endpoints.banking.linkAccount) { case (linkId, req) => bankingService.linkAccount(linkId, req) },
      route(Endpoints.banking.sync)(id => bankingService.sync(id).map(_.map(_.views))),
      read(Endpoints.banking.syncAll)(_ => importJobService.startSyncAll()),
      read(Endpoints.banking.listCardGroups)(_ => bankingService.listCardGroups),
      route(Endpoints.banking.createCardGroup)(dto => bankingService.createCardGroup(dto)),
      route(Endpoints.banking.deleteCardGroup)(id => bankingService.deleteCardGroup(id)),
      route(Endpoints.banking.linkCardGroup) { case (id, req) => bankingService.linkCardGroup(id, req) },
      route(Endpoints.banking.updateCardGroupLimit) { case (id, req) => bankingService.updateCardGroupLimit(id, req) },
      read(Endpoints.banking.importTransactions) { case (id, req) => importJobService.startImport(id, req) },
      // Background import/sync jobs (status, history, errors)
      read(Endpoints.jobs.list)(_ => importJobService.listRecent),
      route(Endpoints.jobs.get)(id => importJobService.get(id).map(_.toRight(ApiError.notFound(s"Import job not found: ${id.value}")))),
      // Transactions
      read(Endpoints.transactions.list) { case (acc, month, cat, hide, sort, asc, limit, q) =>
        transactions.list(acc, month, cat, hide, sort, asc, limit, q)
      },
      read(Endpoints.transactions.months)(_ => repos.bankTransactions.distinctMonths()),
      route(Endpoints.transactions.setCategory) { case (id, dto) => categorization.setCategory(id, dto.categoryId) },
      route(Endpoints.transactions.setNote) { case (id, dto) => transactions.setNote(id, dto) },
      // Categories
      read(Endpoints.categories.list)(_ => repos.categories.findAll),
      read(Endpoints.categories.summaries)(_ => categorySummaries.summaries),
      route(Endpoints.categories.create)(categories.create),
      route(Endpoints.categories.update) { case (id, dto) => categories.update(id, dto) },
      route(Endpoints.categories.delete)(categories.delete),
      route(Endpoints.categories.setOverride) { case (id, dto) => categories.setOverride(id, Some(dto.remainingCents)) },
      route(Endpoints.categories.clearOverride)(id => categories.setOverride(id, None)),
      // Classification rules
      read(Endpoints.rules.list)(_ => repos.classificationRules.findAll),
      route(Endpoints.rules.create)(dto => categorization.writeRule(RuleChange.Create(dto.name, dto.categoryId, dto.criteria))),
      route(Endpoints.rules.update) { case (id, dto) =>
        categorization.writeRule(RuleChange.Update(id, Some(dto.name), Some(dto.categoryId), Some(dto.criteria)))
      },
      route(Endpoints.rules.delete)(id => categorization.applyChange(RuleChange.Delete(id)).map(_.void)),
      read(Endpoints.rules.reorder)(dto => categorization.reorder(dto.orderedIds)),
      read(Endpoints.rules.apply)(_ => ruleEngine.applyRules().map(ApplyRulesResult(_))),
      read(Endpoints.rules.preview)(categorization.previewRule),
      read(Endpoints.rules.exportRules)(_ => categorization.exportRules),
      read(Endpoints.rules.importRules)(categorization.importRules),
      // Analytics (Metabase-backed; reports itself disabled when the deployment ships without it)
      read(Endpoints.analytics.config)(_ => analyticsService.status),
      // Database import/export
      // The export is all the data and the import replaces it: the session alone is not sufficient for either.
      confirmedRoute(Endpoints.database.download)(_ => backup.exportDatabase),
      confirmedRoute(Endpoints.database.`import`)(backup.importDatabase),
    )

    interpreter.toRoutes(routes)
  }
}
