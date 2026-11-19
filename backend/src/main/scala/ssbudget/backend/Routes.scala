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
import ssbudget.backend.analytics.AnalyticsService
import ssbudget.backend.auth.SessionService
import ssbudget.backend.banking.{BankingService, CategorizationService, ImportJobService, RuleChange, RuleEngineService, TransactionImportService}
import ssbudget.backend.db.{Database, Repositories}
import ssbudget.backend.db.repository.{TransactionFilter, TransactionSearchRow}
import ssbudget.backend.search.TransactionSearch
import ssbudget.backend.service.BudgetQueries.*
import ssbudget.backend.service.{BalanceHistory, BudgetSnapshotService, CategorySummaryService, CurrencyService}
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
      categorization: CategorizationService,
      analyticsService: AnalyticsService,
      testMode: Boolean = false,
  ): HttpRoutes[IO] = {
    val interpreter       = Http4sServerInterpreter[IO]()
    val categorySummaries = new CategorySummaryService(repos)
    val budgetSnapshots   = new BudgetSnapshotService(repos)

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
      route(Endpoints.periods.setExpectedEnd)((id, req) => setPeriodExpectedEnd(repos, id, req)),
      route(Endpoints.periods.setPlannedSaving)((id, req) => setPeriodPlannedSaving(repos, id, req)),
      route(Endpoints.periods.summaries)(periodSummaries(repos)),
      route(Endpoints.periods.freeMoneyHistory)(_ => freeMoneyHistory(repos, budgetSnapshots)),
      // Savings
      route(Endpoints.savings.periodBaselines)(_ => savingsPeriodBaselines(repos)),
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
      route(Endpoints.banking.updateCardGroupLimit) { case (id, req) => bankingService.updateCardGroupLimit(id, req) },
      route(Endpoints.banking.importTransactions) { case (id, req) => importJobService.startImport(id, req).map(Right(_)) },
      // Background import/sync jobs (status, history, errors)
      route(Endpoints.jobs.list)(_ => importJobService.listRecent.map(Right(_))),
      route(Endpoints.jobs.get)(id => importJobService.get(id).map(_.toRight(s"Import job not found: ${id.value}"))),
      // Transactions
      route(Endpoints.transactions.list) { case (acc, month, cat, hide, sort, asc, limit, q) =>
        listTransactions(repos)(acc, month, cat, hide, sort, asc, limit, q)
      },
      route(Endpoints.transactions.months)(_ => repos.bankTransactions.distinctMonths().map(Right(_))),
      route(Endpoints.transactions.setCategory) { case (id, dto) => categorization.setCategory(id, dto.categoryId) },
      route(Endpoints.transactions.setNote) { case (id, dto) => setTransactionNote(repos)(id, dto) },
      // Categories
      route(Endpoints.categories.list)(_ => repos.categories.findAll.map(Right(_))),
      route(Endpoints.categories.summaries)(_ => categorySummaries.summaries.map(Right(_))),
      route(Endpoints.categories.create)(createCategory(repos)),
      route(Endpoints.categories.update) { case (id, dto) => updateCategory(repos)(id, dto) },
      route(Endpoints.categories.delete)(deleteCategory(repos, ruleEngine)),
      route(Endpoints.categories.setOverride) { case (id, dto) => setCategoryOverride(repos, categorySummaries)(id, Some(dto.remainingCents)) },
      route(Endpoints.categories.clearOverride)(id => setCategoryOverride(repos, categorySummaries)(id, None)),
      // Classification rules
      route(Endpoints.rules.list)(_ => repos.classificationRules.findAll.map(Right(_))),
      route(Endpoints.rules.create)(dto => ruleWrite(categorization, RuleChange.Create(dto.name, dto.categoryId, dto.criteria))),
      route(Endpoints.rules.update) { case (id, dto) =>
        ruleWrite(categorization, RuleChange.Update(id, Some(dto.name), Some(dto.categoryId), Some(dto.criteria)))
      },
      route(Endpoints.rules.delete)(id => categorization.applyChange(RuleChange.Delete(id)).map(_.void)),
      route(Endpoints.rules.reorder)(reorderRules(repos, ruleEngine)),
      route(Endpoints.rules.apply)(_ => ruleEngine.applyRules().map(n => Right(ApplyRulesResult(n)))),
      route(Endpoints.rules.preview)(previewRule(repos)),
      route(Endpoints.rules.exportRules)(_ => exportRules(repos)),
      route(Endpoints.rules.importRules)(importRules(repos, ruleEngine)),
      // Analytics (Metabase-backed; reports itself disabled when the deployment ships without it)
      route(Endpoints.analytics.config)(_ => analyticsService.status.map(Right(_))),
      // Database import/export
      route(Endpoints.database.download)(_ => exportDatabase(xa, dbPath)),
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
    // Record the opening zero balance. Without it, the first balance set later becomes the account's period-start baseline, and money
    // put into a new account never shows as a change in the period it was added.
    (repos.accounts.create(account) *> repos.accounts.setBalance(accountId, 0L, BalanceSource.Manual, now)).as(Right(account))
  }

  private def updateAccount(repos: Repositories)(id: AccountId, dto: UpdateAccount): Result[Account] = {
    for {
      existingOpt <- repos.accounts.findById(id)
      result      <- existingOpt match {
                       case Some(existing) =>
                         val role = dto.role.getOrElse(existing.role)
                         // A spending account has bank links, card groups and a place in free money. Only the two held roles can change places.
                         if role != existing.role && !(AccountRole.held(role) && AccountRole.held(existing.role)) then {
                           IO.pure(Left("Only a savings account and an investment account can change role"))
                         } else {
                           val updated = existing.copy(name = dto.name, currency = dto.currency, role = role)
                           // Only an investment account has transfer categories.
                           val unlink  = IO.whenA(role != AccountRole.Investment)(repos.categories.clearTransferAccount(id))
                           (repos.accounts.update(updated) *> unlink).as(Right(updated))
                         }
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
                      _ <- repos.categories.clearTransferAccount(id)
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

  /** Correct when a period is expected to end. The date is the period's own — nothing re-derives it — so this is the whole of "the paycheck came a
    * few days early", "it came late", or "the pay cycle changed shape".
    */
  private def setPeriodExpectedEnd(repos: Repositories, id: PeriodId, req: SetPeriodExpectedEndRequest): Result[Period] =
    for {
      existingOpt <- repos.periods.findById(id)
      result      <- existingOpt match {
                       case None           => IO.pure(Left(s"Period not found: ${id.value}"))
                       case Some(existing) =>
                         repos.periods.updateExpectedEnd(id, req.expectedEnd).as(Right(existing.copy(expectedEnd = req.expectedEnd)))
                     }
    } yield result

  /** Set how much a period plans to move to savings. A negative amount has no meaning and is refused. */
  private def setPeriodPlannedSaving(repos: Repositories, id: PeriodId, req: SetPeriodPlannedSavingRequest): Result[Period] =
    if req.plannedSavingCents < 0 then IO.pure(Left("Planned saving cannot be negative"))
    else {
      repos.periods.findById(id).flatMap {
        case None           => IO.pure(Left(s"Period not found: ${id.value}"))
        case Some(existing) =>
          repos.periods.updatePlannedSaving(id, req.plannedSavingCents).as(Right(existing.copy(plannedSavingCents = req.plannedSavingCents)))
      }
    }

  private def startNewPeriod(repos: Repositories): Result[Period] = {
    val now         = Instant.now()
    val newPeriodId = PeriodId(UUID.randomUUID().toString)

    for {
      // Close current period
      currentPeriod <- repos.periods.findCurrent
      // The plan to save is carried to the new period; it is a habit, not a decision of one month.
      newPeriod      = Period(newPeriodId, now, Period.defaultExpectedEnd(now), None, currentPeriod.fold(0L)(_.plannedSavingCents))
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

  /** Balance at the current period's start per savings and investment account (see [[AccountPeriodBaseline]] — the client derives the Δ from its live
    * balances). No current period, or no recorded history for an account → no entry, and the client shows no Δ. Informational only — not part of the
    * free-money calc.
    */
  private def savingsPeriodBaselines(repos: Repositories): Result[List[AccountPeriodBaseline]] =
    for {
      accounts  <- repos.accounts.findAll
      periodOpt <- repos.periods.findCurrent
      baselines <- periodOpt.toList.flatTraverse { p =>
                     val savings    = accounts.filter(a => AccountRole.held(a.role))
                     // The balance at the period's start. An account whose first balance was recorded after that start (bank balances are
                     // recorded on sync, so this is usual for a new link) gets it from the ledger: see [[BalanceHistory]].
                     val (from, to) = periodWindow(p)
                     for {
                       history   <- BalanceHistory.load(repos, savings)
                       converter <- primaryConverter(repos)
                       moved     <- movedToInvestments(repos, converter._2)(from, to)
                     } yield savings.flatMap { acc =>
                       history
                         .asOf(acc.id, p.startDate)
                         .map(b =>
                           AccountPeriodBaseline(
                             acc.id,
                             Money(b, acc.currency),
                             // "Moved" is a figure of investment accounts only.
                             if acc.role == AccountRole.Investment then moved.getOrElse(acc.id, 0L) else 0L,
                           ),
                         )
                     }
                   }
    } yield Right(baselines)

  /** The net amount moved INTO each investment account over `[from, to)`, in the primary currency. Negative = taken out. It has two sources:
    *   - the transfer categories of the account: the opposite of what the bank side shows (a withdrawal arrives at the bank);
    *   - the account's own ledger, when it has one (for example card payments imported from a statement of the broker): its non-internal net, with
    *     its own sign. A payment from the account is money taken out of the investment, and it is spend as on any other account.
    *
    * The map has an entry for other accounts with a ledger too. Callers read only the investment accounts.
    */
  private def movedToInvestments(repos: Repositories, toPrimary: (Long, Currency) => Long)(
      from: Instant,
      to: Option[Instant],
  ): IO[Map[AccountId, Long]] =
    for {
      transfers <- repos.bankTransactions.transfersByAccountBetween(from, to)
      ownLedger <- repos.bankTransactions.netByAccountBetween(from, to)
      uidIndex  <- BalanceHistory.accountUidIndex(repos)
    } yield {
      val viaCategories = transfers.map { case (account, currency, cents) => account -> -toPrimary(cents, currency) }
      val viaLedger     = ownLedger.flatMap { case (uid, currency, cents) => uidIndex.get(uid).map(_ -> toPrimary(cents, currency)) }
      (viaCategories ++ viaLedger).groupMapReduce(_._1)(_._2)(_ + _)
    }

  /** How many periods the retrospective summarizes when the client doesn't ask for a number. Each period costs a handful of aggregate queries, so
    * this is capped rather than unbounded.
    */
  /** The recorded days, brought up to date first.
    *
    * The usual writer is [[ssbudget.backend.service.BudgetSnapshotTrigger]], which runs AFTER a response and is throttled. Without the pass here, the
    * first chart of the day would show the days before the quiet time and stop there. A pass that fails must not fail the chart: the rows already
    * stored are still correct.
    */
  private def freeMoneyHistory(repos: Repositories, snapshots: BudgetSnapshotService): Result[FreeMoneyHistory] =
    for {
      causes <- snapshots.ensureUpToDateWithCauses.handleErrorWith(e => IO.println(s"Daily budget snapshot failed: ${e.getMessage}").as(Nil))
      rows   <- repos.dailyBudgetSnapshots.findAll
    } yield Right(FreeMoneyHistory(rows, causes))

  private val periodSummaryDefaultCount = 24

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
      // Every period needs each account's balance at two instants. The history of each account is read ONCE here, and the boundaries are resolved
      // in memory.
      history   <- BalanceHistory.load(repos, accounts)
      summaries <- periods.traverse(periodSummary(repos, converter, accounts, defs, cats, history))
    } yield Right(summaries)

  private def periodSummary(
      repos: Repositories,
      converter: (Currency, (Long, Currency) => Long),
      accounts: List[Account],
      defs: List[BudgetItemDefinition],
      cats: List[Category],
      history: BalanceHistory,
  )(p: Period): IO[PeriodSummary] = {
    val (primary, toPrimary) = converter

    val (from, to)         = periodWindow(p)
    val transferCategories = cats.filter(_.transferAccount.isDefined).map(_.id).toSet
    val spending           = accounts.filter(_.role == AccountRole.Spending)
    val savings            = accounts.filter(_.role == AccountRole.Savings)
    val investment         = accounts.filter(_.role == AccountRole.Investment)

    // A balance at a period boundary: the recorded balance at or before that instant, and, before the first record of an account, that record
    // carried back through the bank ledger (see [[BalanceHistory]]). So a period from before the balances were recorded shows what the bank
    // ledger says the accounts did, and not zero. A running period has no end: its "balance at the end" is the live balance.
    def balanceAt(acc: Account, at: Option[Instant]): Option[Long] =
      at.fold(Option(acc.balanceCents))(history.asOf(acc.id, _))
    // The change across the period and the balance at its end, for the accounts of one role. At today's rates, as every other figure of the
    // summary. Thus the change of a foreign account is only what moved, not what the rate did.
    def changeAndEnd(group: List[Account]): (Long, Option[Long])   = {
      val change = group.map { acc =>
        // None only for an account with no recorded balance at all.
        (balanceAt(acc, Some(p.startDate)), balanceAt(acc, p.endDate)).mapN((start, end) => toPrimary(end - start, acc.currency)).getOrElse(0L)
      }.sum
      val ends   = group.flatMap(acc => balanceAt(acc, p.endDate).map(toPrimary(_, acc.currency)))
      (change, Option.when(ends.nonEmpty)(ends.sum))
    }
    val (savingsDelta, savingsEnd)                                 = changeAndEnd(savings)
    val (investmentDelta, investmentEnd)                           = changeAndEnd(investment)
    val endBalances                                                = spending.map(acc => balanceAt(acc, p.endDate).map(toPrimary(_, acc.currency)))

    for {
      flows   <- repos.bankTransactions.flowsBetween(from, to)
      moved   <- movedToInvestments(repos, toPrimary)(from, to)
      // A deposit to an investment account is not spend, so a transfer category has no place in the spend breakdown.
      catRows <- repos.bankTransactions.spendByCategoryBetween(from, to).map(_.filterNot(r => transferCategories(r._1)))
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
        savingsEndCents = savingsEnd,
        investmentChangeCents = investmentDelta,
        investmentMovedCents = investment.map(acc => moved.getOrElse(acc.id, 0L)).sum,
        investmentEndCents = investmentEnd,
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
      q: Option[String],
  ): Result[TransactionListResponse] = {
    // The month dropdown carries period sentinels instead of a YYYY-MM bucket; resolve them to the same [from, to) window the category-spend figures
    // use (see periodWindow), so drilling into a category budget lists exactly the transactions its number was computed from.
    val isSentinel = month.exists(m => m == MonthFilter.CurrentPeriod || m == MonthFilter.PreviousPeriod)
    val searchTerm = q.map(_.trim).filter(_.nonEmpty)
    val cap        = limit.getOrElse(transactionPageCap)
    val sortKey    = sort.getOrElse("date")
    val ascending  = asc.getOrElse(false)
    for {
      periodOpt <- month match {
                     case Some(MonthFilter.CurrentPeriod)  => repos.periods.findCurrent
                     case Some(MonthFilter.PreviousPeriod) => repos.periods.findAll.map(previousClosedPeriod)
                     case _                                => IO.pure(Option.empty[Period])
                   }
      window     = periodOpt.map(periodWindow)
      filter     = TransactionFilter(
                     accountUid = accountUid.filter(_.nonEmpty),
                     month = if isSentinel then None else month.filter(_.nonEmpty),
                     from = window.map(_._1),
                     to = window.flatMap(_._2),
                     category = category.filter(_.nonEmpty),
                     hideInternal = hideInternal.getOrElse(false),
                   )
      // A sentinel that resolves to nothing (no period started, or no closed period yet) means "no such window", not "no filter".
      res       <- if isSentinel && periodOpt.isEmpty then IO.pure(TransactionListResponse(Nil, 0, Nil))
                   else
                     searchTerm match {
                       case Some(term) => searchTransactions(repos)(filter, sortKey, ascending, cap, term)
                       case None       =>
                         repos.bankTransactions.query(filter, sortKey, ascending, Some(cap)).map { case (items, total, sums) =>
                           TransactionListResponse(items, total, sums.map { case (cur, cents) => Money(cents, cur) })
                         }
                     }
    } yield Right(res)
  }

  /** The free-text path. The match is diacritic-folded and typo-tolerant, neither of which SQL can express, so it runs in memory: pull every row the
    * other filters admit as a lightweight projection, score it, and only then fetch the full transactions for the page that survives. At this data
    * size (single-digit thousands of rows, ~60 characters of text each) the scan is a couple of milliseconds — far cheaper than the denormalised
    * column and sync triggers that would be needed to push it into SQLite.
    *
    * Exact and near matches are ranked and capped independently, so a flood of approximate hits can never push the literal ones off the page.
    */
  private def searchTransactions(repos: Repositories)(
      filter: TransactionFilter,
      sort: String,
      asc: Boolean,
      cap: Int,
      term: String,
  ): IO[TransactionListResponse] = {
    val parsed = TransactionSearch.parse(term)
    for {
      candidates   <- repos.bankTransactions.searchCandidates(filter)
      scored        = candidates.flatMap { row =>
                        TransactionSearch.tierOf(parsed, TransactionSearch.fold(row.text), Math.abs(row.amountCents)).map(row -> _)
                      }
      (exact, near) = scored.partition(_._2 == TransactionSearch.MatchTier.Exact)
      // Mirrors the ORDER BY the SQL path uses. `booked_at` is stored as an ISO-8601 string, so SQLite compares it lexicographically; that agrees
      // with comparing the instants for the day-precision timestamps banks actually send, and would only part company on a sub-second one.
      ordering      = if sort == "amount" then Ordering.by[TransactionSearchRow, Long](_.amountCents)
                      else Ordering.by[TransactionSearchRow, Instant](_.bookedAt)
      order         = if asc then ordering else ordering.reverse
      exactRows     = exact.map(_._1).sorted(order)
      // The cap is a display budget for the whole page: exact matches claim it first, and near matches get whatever is left. When they get nothing,
      // only the count of them is wanted, and a count doesn't need an order.
      shownExact    = exactRows.take(cap)
      nearBudget    = cap - shownExact.size
      nearRows      = near.map(_._1)
      shownNear     = if nearBudget == 0 then Nil else nearRows.sorted(order).take(nearBudget)
      full         <- repos.bankTransactions.findByIds((shownExact ++ shownNear).map(_.id))
      byId          = full.map(t => t.id -> t).toMap
      // Totals cover the exact tier only, and the FULL exact match rather than the capped page.
      sums          = exactRows.groupBy(_.currency).toList.map { case (cur, rows) => Money(rows.map(_.amountCents).sum, cur) }
    } yield TransactionListResponse(
      items = shownExact.flatMap(r => byId.get(r.id)),
      total = exactRows.size,
      sums = sums,
      near = shownNear.flatMap(r => byId.get(r.id)),
    )
  }

  /** Live rule preview: run the shared matcher over all stored transactions server-side (the browser no longer holds them). */
  private def previewRule(repos: Repositories)(req: RulePreviewRequest): Result[RulePreviewResponse] =
    repos.bankTransactions.list(None, None, None).map { all =>
      val matched = if req.criteria.isEmpty then Nil else all.filter(t => RuleMatcher.matches(req.criteria, t))
      Right(RulePreviewResponse(matched.size, all.size, matched.take(200)))
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
                         val updated = dto.toCategory(id)
                         // A transfer category must name an investment account: only that role splits its change into moved and market.
                         updated.transferAccount.traverse(repos.accounts.findById).map(_.flatten).flatMap {
                           case Some(acc) if acc.role != AccountRole.Investment =>
                             IO.pure(Left(s"${acc.name} is not an investment account"))
                           case None if updated.transferAccount.isDefined       =>
                             IO.pure(Left("The account of this transfer category does not exist"))
                           case _                                               =>
                             repos.categories.update(updated).as(Right(updated))
                         }
                       case None    => IO.pure(Left(s"Category not found: ${id.value}"))
                     }
    } yield result
  }

  /** Sets (`Some`) or clears (`None`) the manual remaining-amount override for a category budget in the CURRENT period, then returns the refreshed
    * summaries. The amount is stored with its sign: positive is still to be spent, negative is still expected to come in (an income category — see
    * [[CategoryBudgetType.remaining]]). 0 marks the budget as covered either way. `V17__category_budget_override.sql` predates income budgets and
    * comments the column as `>= 0`; the signed convention here supersedes it (the column itself has no constraint).
    */
  private def setCategoryOverride(repos: Repositories, summaries: CategorySummaryService)(
      id: CategoryId,
      remainingCents: Option[Long],
  ): Result[List[CategorySummary]] =
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
                       write >> summaries.summaries.map(Right(_))
                   }
    } yield result

  private def deleteCategory(repos: Repositories, ruleEngine: RuleEngineService)(id: CategoryId): Result[Unit] =
    // Detach the category from any transactions and drop rules targeting it, delete it, then re-evaluate (rules for it are gone).
    for {
      _ <- repos.bankTransactions.clearCategory(id)
      _ <- repos.classificationRules.deleteByCategory(id)
      _ <- repos.categoryBudgetOverrides.deleteByCategory(id)
      _ <- repos.categories.delete(id)
      _ <- ruleEngine.applyRules()
    } yield Right(())

  /** A rule create or update through the shared service; the REST answer is the rule itself. */
  private def ruleWrite(categorization: CategorizationService, change: RuleChange): Result[ClassificationRule] =
    categorization.applyChange(change).map(_.flatMap(_.map(_.rule).toRight("The change gave no rule")))

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

  /** A consistent copy of the whole database, via SQLite's own backup API.
    *
    * NOT a raw read of the database file: this database runs in WAL mode, where committed data lives in the `-wal` sidecar until a checkpoint.
    * Copying the main file alone therefore produced a backup that was stale, or — on a freshly checkpointed file — empty, while still carrying a
    * valid SQLite header, so nothing looked wrong until a restore came up missing its tables.
    */
  private def exportDatabase(xa: HikariTransactor[IO], dbPath: String): Result[(String, Array[Byte])] = {
    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss"))
    val filename  = s"ssbudget_backup_$timestamp.db"
    val tempPath  = Paths.get(dbPath + ".export.tmp")

    val snapshot = IO.blocking {
      // Close the POOLED connection, not the unwrapped delegate — see importDatabase.
      val pooled = xa.kernel.getConnection
      try {
        pooled.unwrap(classOf[SQLiteConnection]).getDatabase.backup("main", tempPath.toAbsolutePath.toString, null)
      } finally {
        pooled.close()
      }
      JFiles.readAllBytes(tempPath)
    }

    val cleanup = IO.blocking(if JFiles.exists(tempPath) then JFiles.delete(tempPath))

    snapshot
      .guarantee(cleanup)
      .map(bytes => Right((s"""attachment; filename="$filename"""", bytes)))
      .handleError(e => Left(s"Export failed: ${e.getMessage}"))
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

      // The schema versions in the uploaded file. A file with no Flyway history is not a database of this app. A file with a version that this
      // build does not know comes from a newer build: this build cannot run on it, and the next start would fail.
      val uploadedVersions: IO[Either[String, Set[String]]] = IO.blocking {
        val connection = java.sql.DriverManager.getConnection(s"jdbc:sqlite:${tempPath.toAbsolutePath}")
        try {
          val rows     = connection.createStatement().executeQuery("SELECT version FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL")
          val versions = Iterator.continually(rows).takeWhile(_.next()).map(_.getString(1)).toSet
          Right(versions): Either[String, Set[String]]
        } catch {
          case _: java.sql.SQLException => Left("This file is not an SSBudget database: it has no migration history.")
        } finally connection.close()
      }

      def checked(known: Set[String]): IO[Either[String, Unit]] = uploadedVersions.map(_.flatMap { uploaded =>
        val unknown = uploaded -- known
        Either.cond(
          unknown.isEmpty,
          (),
          s"This backup is from a newer version of the app (schema ${unknown.toList.sorted.mkString(", ")}). Update the app first.",
        )
      })

      // One copy of the database as it was, next to it. A wrong file in the import box is then not the end of the data.
      val keepCurrent = IO.blocking {
        val before = Paths.get(dbPath + ".before-import")
        JFiles.deleteIfExists(before)
        val pooled = xa.kernel.getConnection
        try {
          pooled.unwrap(classOf[SQLiteConnection]).getDatabase.backup("main", before.toAbsolutePath.toString, null)
        } finally {
          pooled.close()
        }
        ()
      }

      val restoreDb = IO.blocking {
        // Close the POOLED connection, not the unwrapped SQLite one. `unwrap` hands back the underlying delegate; closing that shuts the physical
        // connection without ever returning the pool's entry, so every import leaked the pool's only connection and left the app hanging on the next
        // query until it was restarted.
        val pooled = xa.kernel.getConnection
        try {
          pooled.unwrap(classOf[SQLiteConnection]).getDatabase.restore("main", tempPath.toAbsolutePath.toString, null)
        } finally {
          pooled.close()
        }
      }

      // The upload, and the two side files that SQLite makes when the upload is opened for the check.
      val cleanupTemp = IO.blocking {
        List("", "-shm", "-wal").foreach(suffix => JFiles.deleteIfExists(Paths.get(tempPath.toString + suffix)))
      }

      val work = for {
        _       <- writeTemp
        known   <- Database.knownVersions(xa)
        allowed <- checked(known)
        result  <- allowed match {
                     case Left(reason) => IO.pure(Left(reason))
                     case Right(())    =>
                       // An older backup gets the migrations it lacks at once, so the code never runs on an old schema.
                       (keepCurrent *> restoreDb *> Database.migrate(xa))
                         .as(Right("Database imported successfully. Please refresh the page to see the updated data."))
                   }
      } yield result

      work.guarantee(cleanupTemp.handleError(_ => ())).handleError(e => Left(s"Import failed: ${e.getMessage}"))
    }
  }
}
