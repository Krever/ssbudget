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
import ssbudget.backend.banking.{BankingService, RuleEngineService, TransactionImportService}
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
      // Savings transactions
      route(Endpoints.savingsTransactions.listCurrent)(_ => listCurrentPeriodSavingsTransactions(repos)),
      route(Endpoints.savingsTransactions.create)(createSavingsTransaction(repos)),
      route(Endpoints.savingsTransactions.delete)(deleteSavingsTransaction(repos)),
      // One-time expenses
      route(Endpoints.oneTimeExpenses.list)(_ => repos.oneTimeExpenses.findAll.map(Right(_))),
      route(Endpoints.oneTimeExpenses.create)(createOneTimeExpense(repos)),
      route(Endpoints.oneTimeExpenses.update) { case (id, dto) => updateOneTimeExpense(repos)(id, dto) },
      route(Endpoints.oneTimeExpenses.delete)(deleteOneTimeExpense(repos)),
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
      route(Endpoints.banking.sync)(id => bankingService.sync(id)),
      route(Endpoints.banking.syncAll)(_ => syncAllConnections(repos, bankingService, importService)),
      route(Endpoints.banking.listCardGroups)(_ => bankingService.listCardGroups.map(Right(_))),
      route(Endpoints.banking.createCardGroup)(dto => bankingService.createCardGroup(dto)),
      route(Endpoints.banking.deleteCardGroup)(id => bankingService.deleteCardGroup(id)),
      route(Endpoints.banking.linkCardGroup) { case (id, req) => bankingService.linkCardGroup(id, req) },
      route(Endpoints.banking.importTransactions) { case (id, req) => importService.importTransactions(id, req) },
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

  private def listCurrentPeriodSavingsTransactions(repos: Repositories): Result[List[SavingsTransaction]] = {
    for {
      currentPeriod <- repos.periods.findCurrent
      txns          <- currentPeriod.fold(IO.pure(List.empty[SavingsTransaction]))(p => repos.savingsTransactions.findByPeriodId(p.id))
    } yield Right(txns)
  }

  private def createAccount(repos: Repositories)(dto: CreateAccount): Result[Account] = {
    val accountId = AccountId(UUID.randomUUID().toString)
    val now       = Instant.now()
    val account   = Account(accountId, dto.name, dto.currency, dto.role, 0L, dto.savingsTarget, BalanceSource.Manual, Some(now))
    repos.accounts.create(account).as(Right(account))
  }

  private def updateAccount(repos: Repositories)(id: AccountId, dto: UpdateAccount): Result[Account] = {
    for {
      existingOpt <- repos.accounts.findById(id)
      result      <- existingOpt match {
                       case Some(existing) =>
                         val updated = existing.copy(name = dto.name, currency = dto.currency, savingsTarget = dto.savingsTarget)
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
                      _ <- repos.savingsTransactions.deleteByAccountId(id)
                      _ <- repos.accounts.delete(id)
                    } yield Right(())
                }
    } yield result
  }

  private def createBudgetItem(repos: Repositories)(dto: CreateBudgetItem): Result[BudgetItemDefinition] = {
    val itemId = ExpenseDefId(UUID.randomUUID().toString)
    val item   = BudgetItemDefinition(itemId, dto.name, dto.itemType, EstimateMode.Fixed, Some(dto.estimateCents), dto.currency)

    for {
      _             <- repos.expenseDefinitions.create(item)
      // If it's a planned expense or income, create an expense record for the current period
      currentPeriod <- repos.periods.findCurrent
      _             <- currentPeriod match {
                         case Some(period) if dto.itemType == BudgetItemType.PlannedExpense || dto.itemType == BudgetItemType.PlannedIncome =>
                           val recordId = ExpenseRecordId(UUID.randomUUID().toString)
                           val record   = ExpenseRecord(recordId, period.id, itemId, None, None)
                           repos.expenseRecords.create(record)
                         case _                                                                                                             => IO.unit
                       }
    } yield Right(item)
  }

  private def updateBudgetItem(repos: Repositories)(id: ExpenseDefId, dto: UpdateBudgetItem): Result[BudgetItemDefinition] = {
    for {
      existingOpt <- repos.expenseDefinitions.findById(id)
      result      <- existingOpt match {
                       case Some(existing) =>
                         val updated =
                           existing.copy(name = dto.name, itemType = dto.itemType, fixedEstimate = Some(dto.estimateCents), currency = dto.currency)
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

  private def payExpenseRecord(repos: Repositories)(expenseDefId: ExpenseDefId, dto: PayBudgetItem): Result[ExpenseRecord] = {
    for {
      currentPeriod <- repos.periods.findCurrent
      result        <- currentPeriod match {
                         case Some(period) =>
                           for {
                             recordOpt <- repos.expenseRecords.findByPeriodAndExpense(period.id, expenseDefId)
                             record    <- recordOpt match {
                                            case Some(record) =>
                                              val now = Instant.now()
                                              repos.expenseRecords
                                                .markAsPaid(record.id, dto.amountCents, now)
                                                .as(
                                                  record.copy(paidAmount = Some(dto.amountCents), paidAt = Some(now)),
                                                )
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

  private def unpayExpenseRecord(repos: Repositories)(expenseDefId: ExpenseDefId): Result[ExpenseRecord] = {
    for {
      currentPeriod <- repos.periods.findCurrent
      result        <- currentPeriod match {
                         case Some(period) =>
                           for {
                             recordOpt <- repos.expenseRecords.findByPeriodAndExpense(period.id, expenseDefId)
                             record    <- recordOpt match {
                                            case Some(record) =>
                                              // Set paid_amount and paid_at to NULL
                                              val updated = record.copy(paidAmount = None, paidAt = None)
                                              // We need to update the record - let's use markAsPaid with special handling
                                              // Actually, we need to add an unpay method to the repository
                                              // For now, we'll delete and recreate
                                              repos.expenseRecords.delete(record.id) *>
                                                repos.expenseRecords.create(updated).as(updated)
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
      // Create expense records for all planned expenses and incomes
      budgetItems   <- repos.expenseDefinitions.findAll
      plannedItems   = budgetItems.filter(i => i.itemType == BudgetItemType.PlannedExpense || i.itemType == BudgetItemType.PlannedIncome)
      _             <- plannedItems.traverse { item =>
                         val recordId = ExpenseRecordId(UUID.randomUUID().toString)
                         val record   = ExpenseRecord(recordId, newPeriodId, item.id, None, None)
                         repos.expenseRecords.create(record)
                       }
    } yield Right(newPeriod)
  }

  private def createSavingsTransaction(repos: Repositories)(dto: CreateSavingsTransaction): Result[SavingsTransaction] = {
    for {
      currentPeriod <- repos.periods.findCurrent
      accountOpt    <- repos.accounts.findById(dto.accountId)
      result        <- (currentPeriod, accountOpt) match {
                         case (Some(period), Some(account)) if account.role == AccountRole.Savings =>
                           val txnId = SavingsTransactionId(UUID.randomUUID().toString)
                           val now   = Instant.now()
                           val txn   = SavingsTransaction(txnId, dto.accountId, period.id, dto.amount, dto.note, now)
                           // Savings transactions are informational only; they never modify the account balance.
                           repos.savingsTransactions.create(txn).as(Right(txn))
                         case (Some(_), Some(_))                                                   =>
                           IO.pure(Left(s"Account is not a savings account: ${dto.accountId.value}"))
                         case (None, _)                                                            =>
                           IO.pure(Left("No current period found"))
                         case (_, None)                                                            =>
                           IO.pure(Left(s"Savings account not found: ${dto.accountId.value}"))
                       }
    } yield result
  }

  private def deleteSavingsTransaction(repos: Repositories)(id: SavingsTransactionId): Result[Unit] = {
    for {
      txnOpt <- repos.savingsTransactions.findById(id)
      result <- txnOpt match {
                  case Some(_) => repos.savingsTransactions.delete(id).as(Right(()))
                  case None    => IO.pure(Left(s"Savings transaction not found: ${id.value}"))
                }
    } yield result
  }

  private def createOneTimeExpense(repos: Repositories)(dto: CreateOneTimeExpense): Result[OneTimeExpense] = {
    val id      = OneTimeExpenseId(UUID.randomUUID().toString)
    val date    = dto.date.getOrElse(Instant.now())
    val expense = OneTimeExpense(id, dto.name, dto.amountCents, dto.currency, date)

    repos.oneTimeExpenses.create(expense).as(Right(expense))
  }

  private def updateOneTimeExpense(repos: Repositories)(id: OneTimeExpenseId, dto: UpdateOneTimeExpense): Result[OneTimeExpense] = {
    for {
      existingOpt <- repos.oneTimeExpenses.findById(id)
      result      <- existingOpt match {
                       case Some(_) =>
                         val updated = OneTimeExpense(id, dto.name, dto.amountCents, dto.currency, dto.date)
                         repos.oneTimeExpenses.update(updated).as(Right(updated))
                       case None    =>
                         IO.pure(Left(s"One-time expense not found: ${id.value}"))
                     }
    } yield result
  }

  private def deleteOneTimeExpense(repos: Repositories)(id: OneTimeExpenseId): Result[Unit] = {
    repos.oneTimeExpenses.delete(id).as(Right(()))
  }

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
    // The month dropdown carries a "current-period" sentinel; resolve it to the open period's [start, end) window server-side.
    val currentPeriod = month.contains("current-period")
    for {
      periodOpt <- if currentPeriod then repos.periods.findCurrent else IO.pure(Option.empty[Period])
      from       = if currentPeriod then periodOpt.map(periodStartOfDay) else None
      to         = if currentPeriod then periodOpt.flatMap(_.endDate) else None
      monthArg   = if currentPeriod then None else month.filter(_.nonEmpty)
      res       <- repos.bankTransactions.query(
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

  /** One-shot "sync everything": for each authorized connection, sync balances then import transactions (incremental). Resilient — a connection that
    * fails (e.g. expired consent) is recorded in `errors` and the rest still complete. Runs connections sequentially to be gentle on the bank APIs.
    * Balance sync and import are attempted independently so one failing doesn't suppress the other.
    */
  private def syncAllConnections(
      repos: Repositories,
      bankingService: BankingService,
      importService: TransactionImportService,
  ): Result[SyncAllResult] =
    for {
      conns    <- repos.bankConnections.findAll
      active    = conns.filter(_.sessionId.isDefined) // skip connections still pending authorization
      outcomes <- active.traverse { conn =>
                    for {
                      syncE <- bankingService.sync(conn.id).handleError(t => Left(t.getMessage))
                      impE  <- importService.importTransactions(conn.id, ImportTransactionsRequest(None)).handleError(t => Left(t.getMessage))
                    } yield {
                      val errs = List(
                        syncE.left.toOption.map(e => s"${conn.aspspName}: balance sync — $e"),
                        impE.left.toOption.map(e => s"${conn.aspspName}: import — $e"),
                      ).flatten
                      (impE.toOption, errs)
                    }
                  }
      views    <- bankingService.listConnections
    } yield {
      val imports = outcomes.flatMap(_._1)
      val errors  = outcomes.flatMap(_._2)
      val synced  = outcomes.count(_._2.isEmpty)
      Right(SyncAllResult(views, imports.map(_.totalImported).sum, imports.map(_.totalSkipped).sum, synced, errors))
    }

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

  /** Per-category spend stats, converted to the primary currency at the latest rates (mixed-currency categories counted in full). Spend is NET
    * (outflows minus inflows) so pure-inflow categories (salary, refunds) aren't reported as 0 and refunds reduce a category's spend:
    *   - `avgMonthlyCents` = MEAN monthly net spend over the category's active span (see [[monthlyMean]]); current partial month excluded.
    *   - `currentPeriodSpentCents` = net spend since the current period started (from the start of that calendar day).
    *   - `lastPeriodSpentCents` = net spend over the previous (most recent closed) period; 0 if none.
    *   - `currency` = the primary currency.
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
      prevOpt      = periods.filter(_.endDate.isDefined).sortBy(_.startDate.toEpochMilli).lastOption // most recent closed period
      periodStart  = currentOpt.map(periodStartOfDay).getOrElse(currentMonth)
      // NET spend (inflows subtract). All completed-month spend (current partial month excluded by `< currentMonth`), per (cat, currency, YYYY-MM).
      histRows    <- repos.bankTransactions.monthlySpendByCategory(java.time.Instant.EPOCH, currentMonth, includeInflows = true)
      curRows     <- repos.bankTransactions.spendByCategoryBetween(periodStart, None, includeInflows = true)
      prevRows    <- prevOpt match {
                       case Some(p) =>
                         repos.bankTransactions.spendByCategoryBetween(periodStartOfDay(p), p.endDate.map(startOfDayUtc), includeInflows = true)
                       case None    => IO.pure(List.empty[(CategoryId, Currency, Long)])
                     }
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
