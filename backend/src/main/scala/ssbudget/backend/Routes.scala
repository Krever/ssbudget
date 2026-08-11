package ssbudget.backend

import cats.effect.IO
import cats.implicits.*
import doobie.hikari.HikariTransactor
import org.http4s.HttpRoutes
import org.sqlite.SQLiteConnection
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.*
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.http4s.Http4sServerInterpreter
import ssbudget.backend.auth.SessionService
import ssbudget.backend.banking.{BankingService, ImportJobService, RuleEngineService, TransactionImportService}
import ssbudget.backend.db.Repositories
import ssbudget.backend.service.CurrencyService
import ssbudget.shared.api.*
import ssbudget.shared.model.*
import ssbudget.shared.rules.RuleMatcher

import java.nio.file.{Files as JFiles, Paths}
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

object Routes {

  /** Result type for route handlers - IO with Either for error handling. */
  type Result[T] = IO[Either[String, T]]

  def make(
      repos: Repositories,
      xa: HikariTransactor[IO],
      dbPath: String,
      sessionService: SessionService,
      currencyService: CurrencyService,
      bankingService: BankingService,
      importService: TransactionImportService,
      importJobService: ImportJobService,
      ruleEngine: RuleEngineService,
      testMode: Boolean = false,
  ): HttpRoutes[IO] = {
    val interpreter = Http4sServerInterpreter[IO]()

    def validateSession(tokenOpt: Option[String]): IO[Either[String, Unit]] =
      AuthRoutes.validateSession(sessionService, tokenOpt, testMode)

    def route[I, O](ep: Endpoint[Option[String], I, String, O, Any])(h: I => Result[O]): ServerEndpoint[Any, IO] =
      ep.serverSecurityLogic(validateSession).serverLogic(_ => h)

    val routes = List(
      // Accounts (spending + savings, unified)
      route(Endpoints.accounts.list)(_ => repos.accounts.findAll.map(Right(_))),
      route(Endpoints.accounts.create)(createAccount(repos)),
      route(Endpoints.accounts.update) { case (id, dto) => updateAccount(repos)(id, dto) },
      route(Endpoints.accounts.updateBalance) { case (id, dto) => updateAccountBalance(repos)(id, dto) },
      route(Endpoints.accounts.delete)(deleteAccount(repos)),
      // Budget items
      route(Endpoints.budgetItems.list)(_ => repos.expenseDefinitions.findAll.map(Right(_))),
      route(Endpoints.budgetItems.create)(createBudgetItem(repos)),
      route(Endpoints.budgetItems.update) { case (id, dto) => updateBudgetItem(repos)(id, dto) },
      route(Endpoints.budgetItems.delete)(deleteBudgetItem(repos)),
      // Expense records
      route(Endpoints.expenseRecords.listCurrent)(_ => listCurrentPeriodRecords(repos)),
      route(Endpoints.expenseRecords.pay) { case (id, dto) => payExpenseRecord(repos)(id, dto) },
      route(Endpoints.expenseRecords.unpay)(unpayExpenseRecord(repos)),
      // Periods
      route(Endpoints.periods.list)(_ => repos.periods.findAll.map(Right(_))),
      route(Endpoints.periods.startNew)(_ => startNewPeriod(repos)),
      route(Endpoints.periods.summaries)(periodSummaries(repos)),
      // Savings
      route(Endpoints.savings.periodChange)(_ => savingsPeriodChange(repos)),
      // Exchange rates (all rates to primary currency)
      route(Endpoints.exchangeRates.getAll)(_ => getAllExchangeRates(repos)),
      // Currency settings
      route(Endpoints.currencies.getSettings)(_ => currencyService.getSettings().map(Right(_))),
      route(Endpoints.currencies.enable)(dto => currencyService.enableCurrency(dto.code)),
      route(Endpoints.currencies.disable)(code => currencyService.disableCurrency(code)),
      route(Endpoints.currencies.setPrimary)(dto => currencyService.setPrimaryCurrency(dto.code)),
      route(Endpoints.currencies.refreshRates)(_ => currencyService.refreshRates()),
      // Banking (Enable Banking integration)
      route(Endpoints.banking.listAspsps)(country => bankingService.listAspsps(country)),
      route(Endpoints.banking.connect)(req => bankingService.connect(req)),
      route(Endpoints.banking.callback)(req => bankingService.callback(req)),
      route(Endpoints.banking.connections)(_ => bankingService.listConnections.map(Right(_))),
      route(Endpoints.banking.disconnect)(id => bankingService.disconnect(id)),
      route(Endpoints.banking.linkAccount) { case (linkId, req) => bankingService.linkAccount(linkId, req) },
      route(Endpoints.banking.sync)(id => bankingService.sync(id).map(_.map(_.views))),
      route(Endpoints.banking.syncAll)(_ => importJobService.startSyncAll().map(Right(_))),
      route(Endpoints.banking.listCardGroups)(_ => bankingService.listCardGroups.map(Right(_))),
      route(Endpoints.banking.createCardGroup)(dto => bankingService.createCardGroup(dto)),
      route(Endpoints.banking.deleteCardGroup)(id => bankingService.deleteCardGroup(id)),
      route(Endpoints.banking.linkCardGroup) { case (id, req) => bankingService.linkCardGroup(id, req) },
      route(Endpoints.banking.importTransactions) { case (id, req) => importJobService.startImport(id, req).map(Right(_)) },
      // Background import/sync jobs (status, history, errors)
      route(Endpoints.jobs.list)(_ => importJobService.listRecent.map(Right(_))),
      route(Endpoints.jobs.get)(id => importJobService.get(id).map(_.toRight(s"Import job not found: ${id.value}"))),
      // Transactions
      route(Endpoints.transactions.list) { case (acc, month, cat, hide, sort, asc, limit) =>
        listTransactions(repos)(acc, month, cat, hide, sort, asc, limit)
      },
      route(Endpoints.transactions.months)(_ => repos.bankTransactions.distinctMonths().map(Right(_))),
      route(Endpoints.transactions.setCategory) { case (id, dto) => setTransactionCategory(repos)(id, dto) },
      route(Endpoints.transactions.setNote) { case (id, dto) => setTransactionNote(repos)(id, dto) },
      // Categories
      route(Endpoints.categories.list)(_ => repos.categories.findAll.map(Right(_))),
      route(Endpoints.categories.summaries)(_ => categorySummaries(repos)),
      route(Endpoints.categories.create)(createCategory(repos)),
      route(Endpoints.categories.update) { case (id, dto) => updateCategory(repos)(id, dto) },
      route(Endpoints.categories.delete)(deleteCategory(repos, ruleEngine)),
      route(Endpoints.categories.setOverride) { case (id, dto) => setCategoryOverride(repos)(id, Some(dto.remainingCents)) },
      route(Endpoints.categories.clearOverride)(id => setCategoryOverride(repos)(id, None)),
      // Classification rules
      route(Endpoints.rules.list)(_ => repos.classificationRules.findAll.map(Right(_))),
      route(Endpoints.rules.create)(createRule(repos, ruleEngine)),
      route(Endpoints.rules.update) { case (id, dto) => updateRule(repos, ruleEngine)(id, dto) },
      route(Endpoints.rules.delete)(deleteRule(repos, ruleEngine)),
      route(Endpoints.rules.reorder)(reorderRules(repos, ruleEngine)),
      route(Endpoints.rules.apply)(_ => ruleEngine.applyRules().map(n => Right(ApplyRulesResult(n)))),
      route(Endpoints.rules.preview)(previewRule(repos)),
      route(Endpoints.rules.exportRules)(_ => exportRules(repos)),
      route(Endpoints.rules.importRules)(importRules(repos, ruleEngine)),
      // Analytics
      route(Endpoints.analytics.overview)(analyticsOverview(repos)),
      // Database import/export
      route(Endpoints.database.download)(_ => exportDatabase(dbPath)),
      route(Endpoints.database.`import`)(bytes => importDatabase(xa, dbPath, bytes)),
    ) ++ (if testMode then List(route(Endpoints.test.reset)(_ => resetDatabase(repos))) else Nil)

    interpreter.toRoutes(routes)
  }

  private def listCurrentPeriodRecords(repos: Repositories): Result[List[ExpenseRecord]] = {
    for {
      currentPeriod <- repos.periods.findCurrent
      records       <- currentPeriod.fold(IO.pure(List.empty[ExpenseRecord]))(p => repos.expenseRecords.findByPeriod(p.id))
    } yield Right(records)
  }

  private def createAccount(repos: Repositories)(dto: CreateAccount): Result[Account] = {
    val accountId = AccountId(UUID.randomUUID().toString)
    val now       = Instant.now()
    val account   = Account(accountId, dto.name, dto.currency, dto.role, 0L, BalanceSource.Manual, Some(now))
    repos.accounts.create(account).as(Right(account))
  }

  private def updateAccount(repos: Repositories)(id: AccountId, dto: UpdateAccount): Result[Account] = {
    for {
      existingOpt <- repos.accounts.findById(id)
      result      <- existingOpt match {
                       case Some(existing) =>
                         val updated = existing.copy(name = dto.name, currency = dto.currency)
                         repos.accounts.update(updated).as(Right(updated))
                       case None           => IO.pure(Left(s"Account not found: ${id.value}"))
                     }
    } yield result
  }

  private def updateAccountBalance(repos: Repositories)(id: AccountId, dto: UpdateAccountBalance): Result[Account] = {
    for {
      existingOpt <- repos.accounts.findById(id)
      result      <- existingOpt match {
                       case None                                 =>
                         IO.pure(Left(s"Account not found: ${id.value}"))
                       case Some(existing) if !existing.isManual =>
                         IO.pure(Left("This account's balance is driven by a bank sync and cannot be edited manually"))
                       case Some(existing)                       =>
                         val now = Instant.now()
                         repos.accounts
                           .setBalance(id, dto.newBalanceCents, BalanceSource.Manual, now)
                           .as(Right(existing.copy(balanceCents = dto.newBalanceCents, balanceUpdatedAt = Some(now))))
                     }
    } yield result
  }

  private def deleteAccount(repos: Repositories)(id: AccountId): Result[Unit] = {
    for {
      accOpt <- repos.accounts.findById(id)
      result <- accOpt match {
                  case Some(a) if !a.isManual =>
                    IO.pure(Left("This account's balance is driven by a bank link or card group; unlink it first, then delete."))
                  case _                      =>
                    for {
                      _ <- repos.balanceSnapshots.deleteByAccountId(id)
                      _ <- repos.accounts.delete(id)
                    } yield Right(())
                }
    } yield result
  }

  private def createBudgetItem(repos: Repositories)(dto: CreateBudgetItem): Result[BudgetItemDefinition] = {
    val itemId = ExpenseDefId(UUID.randomUUID().toString)
    val item   = BudgetItemDefinition(itemId, dto.name, dto.itemType, dto.estimateCents, dto.currency)

    for {
      _             <- repos.expenseDefinitions.create(item)
      // Open a record for the current period so the item can be paid straight away
      currentPeriod <- repos.periods.findCurrent
      _             <- currentPeriod match {
                         case Some(period) =>
                           val recordId = ExpenseRecordId(UUID.randomUUID().toString)
                           val record   = ExpenseRecord(recordId, period.id, itemId, None, None, settled = false)
                           repos.expenseRecords.create(record)
                         case None         => IO.unit
                       }
    } yield Right(item)
  }

  private def updateBudgetItem(repos: Repositories)(id: ExpenseDefId, dto: UpdateBudgetItem): Result[BudgetItemDefinition] = {
    for {
      existingOpt <- repos.expenseDefinitions.findById(id)
      result      <- existingOpt match {
                       case Some(existing) =>
                         val updated =
                           existing.copy(name = dto.name, itemType = dto.itemType, estimateCents = dto.estimateCents, currency = dto.currency)
                         repos.expenseDefinitions.update(updated).as(Right(updated))
                       case None           =>
                         IO.pure(Left(s"Budget item not found: ${id.value}"))
                     }
    } yield result
  }

  private def deleteBudgetItem(repos: Repositories)(id: ExpenseDefId): Result[Unit] = {
    for {
      // Note: expense records referencing this item should be deleted or we could have FK issues
      // For now, just delete the definition (assuming cascade or manual cleanup)
      _ <- repos.expenseDefinitions.delete(id)
    } yield Right(())
  }

  /** Record a payment against a planned item in the current period. The amount ADDS to what's already been paid, so paying in instalments
    * accumulates; `settle` is what closes the item (see [[PayBudgetItem]]).
    */
  private def payExpenseRecord(repos: Repositories)(expenseDefId: ExpenseDefId, dto: PayBudgetItem): Result[ExpenseRecord] = {
    for {
      currentPeriod <- repos.periods.findCurrent
      result        <- currentPeriod match {
                         case Some(period) =>
                           for {
                             recordOpt <- repos.expenseRecords.findByPeriodAndExpense(period.id, expenseDefId)
                             record    <- recordOpt match {
                                            case Some(record) =>
                                              val updated = record.withPayment(dto.amountCents, Instant.now(), dto.settle)
                                              repos.expenseRecords.savePayment(updated).as(updated)
                                            case None         =>
                                              IO.raiseError(
                                                new Exception(s"Expense record not found for period ${period.id.value} and expense ${expenseDefId.value}"),
                                              )
                                          }
                           } yield Right(record)
                         case None         =>
                           IO.pure(Left("No current period found"))
                       }
    } yield result
  }

  /** Undo all payment progress for the current period: back to nothing paid, nothing settled. */
  private def unpayExpenseRecord(repos: Repositories)(expenseDefId: ExpenseDefId): Result[ExpenseRecord] = {
    for {
      currentPeriod <- repos.periods.findCurrent
      result        <- currentPeriod match {
                         case Some(period) =>
                           for {
                             recordOpt <- repos.expenseRecords.findByPeriodAndExpense(period.id, expenseDefId)
                             record    <- recordOpt match {
                                            case Some(record) =>
                                              val reset = record.cleared
                                              repos.expenseRecords.savePayment(reset).as(reset)
                                            case None         =>
                                              IO.raiseError(new Exception(s"Expense record not found"))
                                          }
                           } yield Right(record)
                         case None         =>
                           IO.pure(Left("No current period found"))
                       }
    } yield result
  }

  private def startNewPeriod(repos: Repositories): Result[Period] = {
    val now         = Instant.now()
    val newPeriodId = PeriodId(UUID.randomUUID().toString)
    val newPeriod   = Period(newPeriodId, now, None)

    for {
      // Close current period
      currentPeriod <- repos.periods.findCurrent
      _             <- currentPeriod.fold(IO.unit)(p => repos.periods.close(p.id, now))
      // Create new period
      _             <- repos.periods.create(newPeriod)
      // Every budget item is a planned item (expense or income), so each one gets a fresh record for the new period.
      budgetItems   <- repos.expenseDefinitions.findAll
      _             <- budgetItems.traverse { item =>
                         val recordId = ExpenseRecordId(UUID.randomUUID().toString)
                         val record   = ExpenseRecord(recordId, newPeriodId, item.id, None, None, settled = false)
                         repos.expenseRecords.create(record)
                       }
    } yield Right(newPeriod)
  }

  /** Net change in savings-account balances over the current period: Σ (current balance − balance as of the period start) across savings accounts,
    * converted to the primary currency. Positive = net saved, negative = net withdrawn. If an account has no snapshot before the period start its
    * pre-period balance is unknown, so we count no change for it. Informational only — not part of the free-money calc.
    */
  private def savingsPeriodChange(repos: Repositories): Result[Money] =
    for {
      accounts   <- repos.accounts.findAll
      savings     = accounts.filter(_.role == AccountRole.Savings)
      periodOpt  <- repos.periods.findCurrent
      primaryOpt <- repos.currencySettings.findPrimary
      enabled    <- repos.currencySettings.findAll
      primary     = primaryOpt.map(_.code).getOrElse(Currency.PLN)
      rateList   <- enabled.filterNot(_.code == primary).traverse(s => repos.exchangeRates.findLatest(s.code, primary))
      changes    <- periodOpt match {
                      case None    => IO.pure(List.empty[(Long, Currency)])
                      case Some(p) =>
                        savings.traverse { acc =>
                          for {
                            atStart  <- repos.balanceSnapshots.balanceAsOf(acc.id, p.startDate)
                            // Baseline = balance at period start, else the earliest recorded balance (first observation ≈ start), else current
                            // (no history → no change). This makes the change reflect what actually moved even when balances were only recorded
                            // mid-period (bank balances are snapshotted on sync, not before the period).
                            baseline <- if atStart.isDefined then IO.pure(atStart) else repos.balanceSnapshots.earliestAmount(acc.id)
                          } yield (acc.balanceCents - baseline.getOrElse(acc.balanceCents), acc.currency)
                        }
                    }
    } yield {
      val rateMap                                     = rateList.flatten.map(r => r.fromCurrency -> r).toMap
      def toPrimary(cents: Long, cur: Currency): Long =
        if cur == primary then cents else rateMap.get(cur).map(_.convert(Money(cents, cur)).amountCents).getOrElse(cents)
      Right(Money(changes.map { case (delta, cur) => toPrimary(delta, cur) }.sum, primary))
    }

  /** The primary currency and a converter into it, at the latest rates: `(cents, currency) => cents in primary`. Anything missing a rate is passed
    * through unconverted (rare — the currency isn't enabled), which is the behaviour every caller here already relied on.
    *
    * Four earlier handlers open-code this same preamble; they are left alone deliberately, but new ones should use this.
    */
  private def primaryConverter(repos: Repositories): IO[(Currency, (Long, Currency) => Long)] =
    for {
      primaryOpt <- repos.currencySettings.findPrimary
      enabled    <- repos.currencySettings.findAll
      primary     = primaryOpt.map(_.code).getOrElse(Currency.PLN)
      rateList   <- enabled.filterNot(_.code == primary).traverse(s => repos.exchangeRates.findLatest(s.code, primary))
    } yield {
      val rates                                       = rateList.flatten.map(r => r.fromCurrency -> r).toMap
      def toPrimary(cents: Long, cur: Currency): Long =
        if cur == primary then cents else rates.get(cur).map(_.convert(Money(cents, cur)).amountCents).getOrElse(cents)
      (primary, toPrimary)
    }

  /** How many periods the retrospective summarizes when the client doesn't ask for a number. Each period costs a handful of aggregate queries, so
    * this is capped rather than unbounded.
    */
  private val periodSummaryDefaultCount = 12

  /** How many categories each period's spend breakdown lists. */
  private val periodSummaryCategoryCap = 6

  /** Per-period retrospective, newest first. See [[PeriodSummary]] for what each figure means and where it comes from. */
  private def periodSummaries(repos: Repositories)(limitOpt: Option[Int]): Result[List[PeriodSummary]] =
    for {
      all       <- repos.periods.findAll
      periods    = all.sortBy(_.startDate.toEpochMilli).reverse.take(limitOpt.filter(_ > 0).getOrElse(periodSummaryDefaultCount).min(60))
      accounts  <- repos.accounts.findAll
      defs      <- repos.expenseDefinitions.findAll
      cats      <- repos.categories.findAll
      converter <- primaryConverter(repos)
      // Every period needs each account's balance at two instants. Read each account's snapshot history ONCE here and resolve the boundaries in memory:
      // asking the repository per (account, boundary, period) was a query per cell of that grid.
      snapshots <- accounts.traverse(acc => repos.balanceSnapshots.findByAccount(acc.id).map(acc.id -> _)).map(_.toMap)
      summaries <- periods.traverse(periodSummary(repos, converter, accounts, defs, cats, snapshots))
    } yield Right(summaries)

  private def periodSummary(
      repos: Repositories,
      converter: (Currency, (Long, Currency) => Long),
      accounts: List[Account],
      defs: List[BudgetItemDefinition],
      cats: List[Category],
      snapshots: Map[AccountId, List[BalanceSnapshot]],
  )(p: Period): IO[PeriodSummary] = {
    val (primary, toPrimary) = converter

    val (from, to) = periodWindow(p)
    val spending   = accounts.filter(_.role == AccountRole.Spending)
    val savings    = accounts.filter(_.role == AccountRole.Savings)

    // Balances come from snapshots, which are stamped at real instants — so they use the period's exact bounds rather than the transaction window's day
    // boundaries. A running period has no end, and its "balance at the end" is simply the account's live balance. `findByAccount` returns newest first,
    // so the balance as of an instant is the first snapshot recorded at or before it.
    def balanceAt(acc: Account, at: Option[Instant]): Option[Long] =
      at.fold(Option(acc.balanceCents))(i => snapshots.getOrElse(acc.id, Nil).find(!_.recordedAt.isAfter(i)).map(_.amount))

    val savingsDelta = savings.map { acc =>
      (balanceAt(acc, Some(p.startDate)), balanceAt(acc, p.endDate)) match {
        case (Some(start), Some(end)) => toPrimary(end - start, acc.currency)
        // Nothing recorded before the period began, so what moved during it is unknowable — count zero rather than invent a baseline.
        case _                        => 0L
      }
    }.sum
    val endBalances  = spending.map(acc => balanceAt(acc, p.endDate).map(toPrimary(_, acc.currency)))

    for {
      flows   <- repos.bankTransactions.flowsBetween(from, to)
      catRows <- repos.bankTransactions.spendByCategoryBetween(from, to)
      records <- repos.expenseRecords.findByPeriod(p.id)
    } yield {
      val byId                                                             = defs.map(d => d.id -> d).toMap
      // A record whose definition has since been deleted carries neither an estimate nor a type, so it can't be attributed to expenses or income.
      val withDefs                                                         = records.flatMap(r => byId.get(r.expenseDefId).map(r -> _))
      val expenses                                                         = withDefs.filter(_._2.itemType == BudgetItemType.PlannedExpense)
      val incomes                                                          = withDefs.filter(_._2.itemType == BudgetItemType.PlannedIncome)
      def paidSum(rows: List[(ExpenseRecord, BudgetItemDefinition)]): Long = rows.map { case (r, d) => toPrimary(r.paidCents, d.currency) }.sum
      val catIndex                                                         = cats.map(c => c.id -> c).toMap
      val topCategories                                                    = catRows
        .groupBy(_._1)
        .view
        .mapValues(_.map { case (_, cur, cents) => toPrimary(cents, cur) }.sum)
        .toList
        .flatMap { case (id, cents) => catIndex.get(id).map(PeriodCategorySpend(_, cents)) }
        .filter(_.spentCents > 0)
        .sortBy(-_.spentCents)
        .take(periodSummaryCategoryCap)

      PeriodSummary(
        period = p,
        days = periodDays(p),
        currency = primary,
        inflowCents = flows.map { case (cur, in, _) => toPrimary(in, cur) }.sum,
        outflowCents = flows.map { case (cur, _, out) => toPrimary(out, cur) }.sum,
        plannedPaidCents = paidSum(expenses),
        plannedEstimateCents = expenses.map { case (_, d) => toPrimary(d.estimateCents, d.currency) }.sum,
        incomeReceivedCents = paidSum(incomes),
        plannedSettled = expenses.count(_._1.settled),
        plannedTotal = expenses.size,
        savingsChangeCents = savingsDelta,
        // Partial by design: accounts without a snapshot that early are left out rather than sinking the whole figure to None.
        endBalanceCents = Option.when(endBalances.exists(_.isDefined))(endBalances.flatten.sum),
        topCategories = topCategories,
      )
    }
  }

  /** A period's length in whole days: its full span once closed, elapsed so far while it's running. */
  private def periodDays(p: Period): Int =
    java.time.temporal.ChronoUnit.DAYS.between(p.startDate, p.endDate.getOrElse(Instant.now())).toInt

  private def resetDatabase(repos: Repositories): Result[Unit] = {
    // This is a test-only endpoint to reset the database
    // In a real implementation, you'd want to be more careful here
    IO.pure(Right(()))
  }

  private def getAllExchangeRates(repos: Repositories): Result[List[ExchangeRate]] = {
    // Get latest exchange rates for all enabled currencies to the primary currency
    for {
      primaryOpt <- repos.currencySettings.findPrimary
      enabled    <- repos.currencySettings.findAll
      primary     = primaryOpt.map(_.code).getOrElse(Currency.PLN)
      // For each non-primary enabled currency, get latest rate to primary
      rates      <- enabled
                      .filterNot(_.code == primary)
                      .traverse { setting =>
                        repos.exchangeRates.findLatest(setting.code, primary)
                      }
    } yield Right(rates.flatten)
  }

  /** Accepts either a full ISO instant or a bare `yyyy-MM-dd` date (interpreted at UTC start-of-day). */
  /** Cap the number of rows shipped to the browser; the response carries the true total so the UI can prompt the user to narrow filters. */
  private val transactionPageCap = 500

  private def listTransactions(repos: Repositories)(
      accountUid: Option[String],
      month: Option[String],
      category: Option[String],
      hideInternal: Option[Boolean],
      sort: Option[String],
      asc: Option[Boolean],
      limit: Option[Int],
  ): Result[TransactionListResponse] = {
    // The month dropdown carries period sentinels instead of a YYYY-MM bucket; resolve them to the same [from, to) window the category-spend figures
    // use (see periodWindow), so drilling into a category budget lists exactly the transactions its number was computed from.
    val isSentinel = month.exists(m => m == MonthFilter.CurrentPeriod || m == MonthFilter.PreviousPeriod)
    for {
      periodOpt <- month match {
                     case Some(MonthFilter.CurrentPeriod)  => repos.periods.findCurrent
                     case Some(MonthFilter.PreviousPeriod) => repos.periods.findAll.map(previousClosedPeriod)
                     case _                                => IO.pure(Option.empty[Period])
                   }
      window     = periodOpt.map(periodWindow)
      from       = window.map(_._1)
      to         = window.flatMap(_._2)
      monthArg   = if isSentinel then None else month.filter(_.nonEmpty)
      // A sentinel that resolves to nothing (no period started, or no closed period yet) means "no such window", not "no filter".
      res       <- if isSentinel && periodOpt.isEmpty then IO.pure((List.empty[BankTransaction], 0, List.empty[(Currency, Long)]))
                   else
                     repos.bankTransactions.query(
                       accountUid.filter(_.nonEmpty),
                       monthArg,
                       from,
                       to,
                       category.filter(_.nonEmpty),
                       hideInternal.getOrElse(false),
                       sort.getOrElse("date"),
                       asc.getOrElse(false),
                       Some(limit.getOrElse(transactionPageCap)),
                     )
    } yield {
      val (items, total, sums) = res
      Right(TransactionListResponse(items, total, sums.map { case (cur, cents) => Money(cents, cur) }))
    }
  }

  /** The most recent closed period, i.e. the one that ended when the current period started. */
  private def previousClosedPeriod(periods: List[Period]): Option[Period] =
    periods.filter(_.endDate.isDefined).sortBy(_.startDate.toEpochMilli).lastOption

  /** Live rule preview: run the shared matcher over all stored transactions server-side (the browser no longer holds them). */
  private def previewRule(repos: Repositories)(req: RulePreviewRequest): Result[RulePreviewResponse] =
    repos.bankTransactions.list(None, None, None).map { all =>
      val matched = if req.criteria.isEmpty then Nil else all.filter(t => RuleMatcher.matches(req.criteria, t))
      Right(RulePreviewResponse(matched.size, all.size, matched.take(200)))
    }

  private def setTransactionCategory(repos: Repositories)(id: BankTransactionId, dto: SetCategoryRequest): Result[BankTransaction] = {
    for {
      txOpt    <- repos.bankTransactions.findById(id)
      catValid <- dto.categoryId match {
                    case Some(cid) => repos.categories.findById(cid).map(o => Either.cond(o.isDefined, (), "Category not found"))
                    case None      => IO.pure(Right(()))
                  }
      result   <- (txOpt, catValid) match {
                    case (None, _)            => IO.pure(Left(s"Transaction not found: ${id.value}"))
                    case (_, Left(err))       => IO.pure(Left(err))
                    case (Some(tx), Right(_)) =>
                      val source = dto.categoryId.map(_ => CategorySource.Manual)
                      repos.bankTransactions
                        .setCategory(id, dto.categoryId)
                        .as(Right(tx.copy(categoryId = dto.categoryId, categorySource = source)))
                  }
    } yield result
  }

  private def setTransactionNote(repos: Repositories)(id: BankTransactionId, dto: SetNoteRequest): Result[BankTransaction] =
    for {
      txOpt  <- repos.bankTransactions.findById(id)
      result <- txOpt match {
                  case None     => IO.pure(Left(s"Transaction not found: ${id.value}"))
                  case Some(tx) =>
                    val cleaned = dto.note.map(_.trim).filter(_.nonEmpty) // blank note clears it
                    repos.bankTransactions.setNote(id, cleaned).as(Right(tx.copy(note = cleaned)))
                }
    } yield result

  private def createCategory(repos: Repositories)(dto: CreateCategory): Result[Category] = {
    val category = Category(CategoryId(UUID.randomUUID().toString), dto.name, dto.color, dto.budgetType)
    repos.categories.create(category).as(Right(category))
  }

  private def updateCategory(repos: Repositories)(id: CategoryId, dto: UpdateCategory): Result[Category] = {
    for {
      existingOpt <- repos.categories.findById(id)
      result      <- existingOpt match {
                       case Some(_) =>
                         val updated = Category(id, dto.name, dto.color, dto.budgetType)
                         repos.categories.update(updated).as(Right(updated))
                       case None    => IO.pure(Left(s"Category not found: ${id.value}"))
                     }
    } yield result
  }

  /** Sets (`Some`) or clears (`None`) the manual remaining-amount override for a category budget in the CURRENT period, then returns the refreshed
    * summaries. The amount is stored with its sign: positive is still to be spent, negative is still expected to come in (an income category — see
    * [[CategoryBudgetType.remaining]]). 0 marks the budget as covered either way. `V17__category_budget_override.sql` predates income budgets and
    * comments the column as `>= 0`; the signed convention here supersedes it (the column itself has no constraint).
    */
  private def setCategoryOverride(repos: Repositories)(id: CategoryId, remainingCents: Option[Long]): Result[List[CategorySummary]] =
    for {
      catOpt    <- repos.categories.findById(id)
      periodOpt <- repos.periods.findCurrent
      result    <- (catOpt, periodOpt) match {
                     case (None, _)               => IO.pure(Left(s"Category not found: ${id.value}"))
                     case (_, None)               => IO.pure(Left("No active period"))
                     case (Some(_), Some(period)) =>
                       val write = remainingCents match {
                         case Some(cents) => IO.realTimeInstant.flatMap(repos.categoryBudgetOverrides.upsert(period.id, id, cents, _))
                         case None        => repos.categoryBudgetOverrides.delete(period.id, id)
                       }
                       write >> categorySummaries(repos)
                   }
    } yield result

  /** Month index (year*12 + month) for a "YYYY-MM" bucket, so we can count how many calendar months a range spans. */
  private def monthIndex(ym: String): Int =
    ym.split("-") match {
      case Array(y, m) => y.toInt * 12 + m.toInt
      case _           => 0
    }

  /** Mean monthly spend over the category's ACTIVE span: total spend divided by the number of calendar months from its first to its last
    * month-with-spend (inclusive). `monthMap` holds only months that actually had spend (primary-currency cents), so its min/max keys are the active
    * span — empty months before the first / after the last are NOT counted (a recently-started or long-dormant category isn't diluted by leading or
    * trailing zeros), while interior gap months are counted as zero (amortised).
    */
  private def monthlyMean(monthMap: Map[String, Long]): Long =
    if monthMap.isEmpty then 0L
    else {
      val idxs = monthMap.keys.map(monthIndex)
      val span = idxs.max - idxs.min + 1
      if span <= 0 then 0L else monthMap.values.sum / span
    }

  private def startOfDayUtc(i: Instant): Instant =
    java.time.LocalDate.ofInstant(i, java.time.ZoneOffset.UTC).atStartOfDay(java.time.ZoneOffset.UTC).toInstant

  /** Start of a period's first calendar day (UTC). Bank `booked_at` is date-at-midnight, but a period starts at the paycheck instant (an afternoon
    * time), so comparing against the raw instant drops the whole start day's spend. Filtering from midnight of that day includes the start-day
    * transactions (bank data is date-granular, so we can't tell pre- vs post-paycheck spend anyway).
    */
  private def periodStartOfDay(p: Period): Instant = startOfDayUtc(p.startDate)

  /** A period's `[from, to)` transaction window: midnight of its first day (see [[periodStartOfDay]]) up to, but excluding, midnight of the day it
    * ended (open-ended while it's still running). Used both to total a category's spend and to list the transactions behind that total, so the two
    * can't drift apart.
    */
  private def periodWindow(p: Period): (Instant, Option[Instant]) = (periodStartOfDay(p), p.endDate.map(startOfDayUtc))

  /** Per-category spend stats, converted to the primary currency at the latest rates (mixed-currency categories counted in full). Spend is NET
    * (outflows minus inflows) so pure-inflow categories (salary, refunds) aren't reported as 0 and refunds reduce a category's spend:
    *   - `avgMonthlyCents` = MEAN monthly net spend over the category's active span (see [[monthlyMean]]); current partial month excluded.
    *   - `currentPeriodSpentCents` = net spend since the current period started (from the start of that calendar day).
    *   - `lastPeriodSpentCents` = net spend over the previous (most recent closed) period; 0 if none.
    *   - `currency` = the primary currency.
    *   - `overrideRemainingCents` = the user's manual remaining-amount override for the current period, when set.
    */

  private def categorySummaries(repos: Repositories): Result[List[CategorySummary]] =
    for {
      cats        <- repos.categories.findAll
      periods     <- repos.periods.findAll
      primaryOpt  <- repos.currencySettings.findPrimary
      enabled     <- repos.currencySettings.findAll
      primary      = primaryOpt.map(_.code).getOrElse(Currency.PLN)
      rateList    <- enabled.filterNot(_.code == primary).traverse(s => repos.exchangeRates.findLatest(s.code, primary))
      now          = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
      firstOfMonth = now.withDayOfMonth(1)
      currentMonth = firstOfMonth.atStartOfDay(java.time.ZoneOffset.UTC).toInstant
      currentOpt   = periods.find(_.endDate.isEmpty)
      prevOpt      = previousClosedPeriod(periods)
      periodStart  = currentOpt.map(periodStartOfDay).getOrElse(currentMonth)
      // NET spend (inflows subtract). All completed-month spend (current partial month excluded by `< currentMonth`), per (cat, currency, YYYY-MM).
      histRows    <- repos.bankTransactions.monthlySpendByCategory(java.time.Instant.EPOCH, currentMonth, includeInflows = true)
      curRows     <- repos.bankTransactions.spendByCategoryBetween(periodStart, None, includeInflows = true)
      prevRows    <- prevOpt match {
                       case Some(p) =>
                         val (from, to) = periodWindow(p)
                         repos.bankTransactions.spendByCategoryBetween(from, to, includeInflows = true)
                       case None    => IO.pure(List.empty[(CategoryId, Currency, Long)])
                     }
      // Manual remaining-amount overrides apply to the current period only.
      overrides   <- currentOpt.traverse(p => repos.categoryBudgetOverrides.findByPeriod(p.id)).map(_.getOrElse(Map.empty))
    } yield {
      val rateMap                                     = rateList.flatten.map(r => r.fromCurrency -> r).toMap
      // Convert cents in any currency to the primary currency; falls back to 1:1 if a rate is missing (rare — currency not enabled).
      def toPrimary(cents: Long, cur: Currency): Long =
        if cur == primary then cents else rateMap.get(cur).map(_.convert(Money(cents, cur)).amountCents).getOrElse(cents)

      // category -> (YYYY-MM -> primary-currency net spend, summed across currencies); only months that had activity are present.
      val byCatMonth                                                                = histRows
        .groupBy(_._1)
        .view
        .mapValues(rows => rows.groupBy(_._3).view.mapValues(_.map { case (_, cur, _, cents) => toPrimary(cents, cur) }.sum).toMap)
        .toMap
      def sumByCat(rows: List[(CategoryId, Currency, Long)]): Map[CategoryId, Long] =
        rows.groupBy(_._1).view.mapValues(_.map { case (_, cur, cents) => toPrimary(cents, cur) }.sum).toMap
      val curByCat                                                                  = sumByCat(curRows)
      val prevByCat                                                                 = sumByCat(prevRows)
      Right(cats.map { cat =>
        val monthMap = byCatMonth.getOrElse(cat.id, Map.empty[String, Long])
        CategorySummary(
          cat,
          avgMonthlyCents = monthlyMean(monthMap),
          currentPeriodSpentCents = curByCat.getOrElse(cat.id, 0L),
          lastPeriodSpentCents = prevByCat.getOrElse(cat.id, 0L),
          currency = primary,
          overrideRemainingCents = overrides.get(cat.id),
        )
      })
    }

  /** Default number of calendar months in the per-category spending breakdown when the client doesn't specify one. */
  private val analyticsDefaultMonths = 12

  /** How many uncategorized-counterparty rows to surface for rule creation. */
  private val analyticsUncategorizedCap = 15

  /** Analytics page payload, all converted to the primary currency:
    *   - a per-month, per-category spending breakdown over the last `months` calendar months (current month included),
    *   - import/categorization health counts,
    *   - the top counterparties still lacking a category (actionable for new rules).
    */
  private def analyticsOverview(repos: Repositories)(monthsOpt: Option[Int]): Result[AnalyticsResponse] = {
    val window = monthsOpt.filter(_ > 0).getOrElse(analyticsDefaultMonths).min(60)
    for {
      cats         <- repos.categories.findAll
      primaryOpt   <- repos.currencySettings.findPrimary
      enabled      <- repos.currencySettings.findAll
      primary       = primaryOpt.map(_.code).getOrElse(Currency.PLN)
      rateList     <- enabled.filterNot(_.code == primary).traverse(s => repos.exchangeRates.findLatest(s.code, primary))
      today         = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
      firstOfMonth  = today.withDayOfMonth(1)
      // Oldest → newest, `window` buckets ending with the current month.
      monthLabels   = (0 until window).reverse.map(i => firstOfMonth.minusMonths(i)).map(d => f"${d.getYear}-${d.getMonthValue}%02d").toList
      windowStart   = firstOfMonth.minusMonths((window - 1).toLong).atStartOfDay(java.time.ZoneOffset.UTC).toInstant
      windowEnd     = firstOfMonth.plusMonths(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant
      spendRows    <- repos.bankTransactions.monthlySpendByCategory(windowStart, windowEnd)
      counts       <- repos.bankTransactions.categorizationCounts()
      uncatByCur   <- repos.bankTransactions.uncategorizedOutflowByCurrency()
      topUncatRows <- repos.bankTransactions.topUncategorizedCounterparties(analyticsUncategorizedCap)
    } yield {
      val rateMap                                     = rateList.flatten.map(r => r.fromCurrency -> r).toMap
      def toPrimary(cents: Long, cur: Currency): Long =
        if cur == primary then cents else rateMap.get(cur).map(_.convert(Money(cents, cur)).amountCents).getOrElse(cents)

      // category id -> (YYYY-MM -> primary-currency spend, summed across currencies)
      val byCatMonth = spendRows
        .groupBy(_._1)
        .view
        .mapValues(rows => rows.groupBy(_._3).view.mapValues(_.map { case (_, cur, _, cents) => toPrimary(cents, cur) }.sum).toMap)
        .toMap

      val catById = cats.map(c => c.id -> c).toMap
      val series  = byCatMonth.toList
        .flatMap { case (catId, monthMap) =>
          catById.get(catId).map { cat =>
            val monthly = monthLabels.map(m => monthMap.getOrElse(m, 0L))
            CategorySpendSeries(cat, monthly, monthly.sum)
          }
        }
        .filter(_.total > 0)
        .sortBy(-_.total)

      val monthlyTotals = monthLabels.zipWithIndex.map { case (_, i) => series.map(_.monthly(i)).sum }

      val (total, internal, categorized, uncategorized, manual, rule) = counts
      val uncatOutflow                                                = uncatByCur.map { case (cur, cents) => toPrimary(cents, cur) }.sum
      val stats                                                       = CategorizationStats(total, internal, categorized, uncategorized, manual, rule, uncatOutflow)

      // Merge the per-currency counterparty rows into primary-currency totals, then re-rank.
      val topUncategorized = topUncatRows
        .groupBy { case (name, _, _, _) => name.getOrElse("(unknown)") }
        .view
        .map { case (name, rows) =>
          UncategorizedCounterparty(
            name,
            rows.map { case (_, _, c, _) => c }.sum,
            rows.map { case (_, cur, _, cents) => toPrimary(cents, cur) }.sum,
          )
        }
        .toList
        .sortBy(-_.outflowCents)

      Right(AnalyticsResponse(primary, monthLabels, series, monthlyTotals, stats, topUncategorized))
    }
  }

  private def deleteCategory(repos: Repositories, ruleEngine: RuleEngineService)(id: CategoryId): Result[Unit] =
    // Detach the category from any transactions and drop rules targeting it, delete it, then re-evaluate (rules for it are gone).
    for {
      _ <- repos.bankTransactions.clearCategory(id)
      _ <- repos.classificationRules.deleteByCategory(id)
      _ <- repos.categoryBudgetOverrides.deleteByCategory(id)
      _ <- repos.categories.delete(id)
      _ <- ruleEngine.applyRules()
    } yield Right(())

  private def createRule(repos: Repositories, ruleEngine: RuleEngineService)(dto: CreateRuleRequest): Result[ClassificationRule] =
    if dto.criteria.isEmpty then IO.pure(Left("A rule must have at least one criterion"))
    else
      repos.categories.findById(dto.categoryId).flatMap {
        case None    => IO.pure(Left("Category not found"))
        case Some(_) =>
          for {
            priority <- repos.classificationRules.nextPriority
            now      <- IO.realTimeInstant
            rule      = ClassificationRule(ClassificationRuleId(UUID.randomUUID().toString), dto.name, dto.categoryId, priority, dto.criteria, now)
            _        <- repos.classificationRules.create(rule)
            _        <- ruleEngine.applyRules()
          } yield Right(rule)
      }

  private def updateRule(
      repos: Repositories,
      ruleEngine: RuleEngineService,
  )(id: ClassificationRuleId, dto: UpdateRuleRequest): Result[ClassificationRule] =
    if dto.criteria.isEmpty then IO.pure(Left("A rule must have at least one criterion"))
    else
      for {
        existingOpt <- repos.classificationRules.findById(id)
        catOpt      <- repos.categories.findById(dto.categoryId)
        result      <- (existingOpt, catOpt) match {
                         case (None, _)                 => IO.pure(Left(s"Rule not found: ${id.value}"))
                         case (_, None)                 => IO.pure(Left("Category not found"))
                         case (Some(existing), Some(_)) =>
                           val updated = existing.copy(name = dto.name, categoryId = dto.categoryId, criteria = dto.criteria)
                           for {
                             _ <- repos.classificationRules.update(id, dto.name, dto.categoryId, dto.criteria)
                             _ <- ruleEngine.applyRules()
                           } yield Right(updated)
                       }
      } yield result

  private def deleteRule(repos: Repositories, ruleEngine: RuleEngineService)(id: ClassificationRuleId): Result[Unit] =
    for {
      _ <- repos.classificationRules.delete(id)
      _ <- ruleEngine.applyRules()
    } yield Right(())

  private def reorderRules(repos: Repositories, ruleEngine: RuleEngineService)(dto: ReorderRulesRequest): Result[List[ClassificationRule]] =
    for {
      _     <- repos.classificationRules.reorder(dto.orderedIds)
      _     <- ruleEngine.applyRules()
      rules <- repos.classificationRules.findAll
    } yield Right(rules)

  /** Export all rules in portable form (category carried by name, order = priority). */
  private def exportRules(repos: Repositories): Result[RulesExport] =
    for {
      rules <- repos.classificationRules.findAll // ordered by priority
      cats  <- repos.categories.findAll
    } yield {
      val nameById = cats.map(c => c.id -> c.name).toMap
      Right(RulesExport(version = 1, rules = rules.map(r => RuleExport(r.name, nameById.getOrElse(r.categoryId, ""), r.criteria))))
    }

  /** Import a rules bundle atomically: (optionally) clear existing rules, create any categories referenced by name, then create the rules in order.
    */
  private def importRules(repos: Repositories, ruleEngine: RuleEngineService)(req: ImportRulesRequest): Result[ImportRulesResult] =
    for {
      _                             <- if req.replace then repos.classificationRules.deleteAll else IO.unit
      existingCats                  <- repos.categories.findAll
      base                          <- if req.replace then IO.pure(0) else repos.classificationRules.nextPriority
      now                           <- IO.realTimeInstant
      // Resolve each referenced category by name (case-insensitive), creating the ones that don't exist yet.
      resolved                      <- req.bundle.rules.map(_.categoryName).distinct.foldLeft(IO.pure((existingCats.map(c => c.name.toLowerCase -> c.id).toMap, 0))) {
                                         (accIO, name) =>
                                           accIO.flatMap { case (byName, created) =>
                                             if byName.contains(name.toLowerCase) then IO.pure((byName, created))
                                             else {
                                               val cat = Category(CategoryId(UUID.randomUUID().toString), name, None)
                                               repos.categories.create(cat).as((byName + (name.toLowerCase -> cat.id), created + 1))
                                             }
                                           }
                                       }
      (catByName, categoriesCreated) = resolved
      toCreate                       = req.bundle.rules.zipWithIndex.map { case (r, idx) =>
                                         ClassificationRule(
                                           ClassificationRuleId(UUID.randomUUID().toString),
                                           r.name,
                                           catByName(r.categoryName.toLowerCase),
                                           base + idx,
                                           r.criteria,
                                           now,
                                         )
                                       }
      _                             <- toCreate.traverse_(repos.classificationRules.create)
      _                             <- ruleEngine.applyRules()
    } yield Right(ImportRulesResult(toCreate.size, categoriesCreated))

  private def exportDatabase(dbPath: String): Result[(String, Array[Byte])] = {
    val path      = Paths.get(dbPath)
    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss"))
    val filename  = s"ssbudget_backup_$timestamp.db"

    IO.blocking {
      if JFiles.exists(path) then {
        val bytes              = JFiles.readAllBytes(path)
        val contentDisposition = s"""attachment; filename="$filename""""
        Right((contentDisposition, bytes))
      } else {
        Left("Database file not found")
      }
    }
  }

  private def importDatabase(xa: HikariTransactor[IO], dbPath: String, bytes: Array[Byte]): Result[String] = {
    val tempPath = Paths.get(dbPath + ".import.tmp")

    // Validate SQLite header
    def isValidSqlite: Boolean = {
      if bytes.length >= 16 then {
        val header   = bytes.take(16)
        val expected = "SQLite format 3\u0000".getBytes("UTF-8")
        header.sameElements(expected)
      } else {
        false
      }
    }

    if !isValidSqlite then {
      IO.pure(Left("Invalid SQLite file. Upload must be a valid SQLite database."))
    } else {
      val writeTemp = IO.blocking {
        val parentDir = tempPath.getParent
        if parentDir != null && !JFiles.exists(parentDir) then {
          JFiles.createDirectories(parentDir)
        }
        JFiles.write(tempPath, bytes)
      }

      val restoreDb = IO.blocking {
        val hikariDs = xa.kernel
        val destConn = hikariDs.getConnection.unwrap(classOf[SQLiteConnection])
        try {
          destConn.getDatabase.restore("main", tempPath.toAbsolutePath.toString, null)
        } finally {
          destConn.close()
        }
      }

      val cleanupTemp = IO.blocking {
        if JFiles.exists(tempPath) then {
          JFiles.delete(tempPath)
        }
      }

      (writeTemp *> restoreDb *> cleanupTemp)
        .as(Right("Database imported successfully. Please refresh the page to see the updated data."))
        .handleError(e => Left(s"Import failed: ${e.getMessage}"))
    }
  }
}
