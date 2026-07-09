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
import ssbudget.backend.banking.BankingService
import ssbudget.backend.db.Repositories
import ssbudget.backend.service.CurrencyService
import ssbudget.shared.api.*
import ssbudget.shared.model.*

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
      route(Endpoints.banking.listCardGroups)(_ => bankingService.listCardGroups.map(Right(_))),
      route(Endpoints.banking.createCardGroup)(dto => bankingService.createCardGroup(dto)),
      route(Endpoints.banking.deleteCardGroup)(id => bankingService.deleteCardGroup(id)),
      route(Endpoints.banking.linkCardGroup) { case (id, req) => bankingService.linkCardGroup(id, req) },
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

  private def createSavingsTransaction(repos: Repositories)(dto: CreateSavingsTransaction): Result[SavingsTransactionResponse] = {
    for {
      currentPeriod <- repos.periods.findCurrent
      accountOpt    <- repos.accounts.findById(dto.accountId)
      result        <- (currentPeriod, accountOpt) match {
                         case (Some(period), Some(account)) if account.role == AccountRole.Savings =>
                           val txnId = SavingsTransactionId(UUID.randomUUID().toString)
                           val now   = Instant.now()
                           val txn   = SavingsTransaction(txnId, dto.accountId, period.id, dto.amount, dto.note, now)
                           for {
                             _       <- repos.savingsTransactions.create(txn)
                             updated <- repos.accounts.adjustBalance(dto.accountId, dto.amount, now)
                           } yield updated.toRight("Failed to update account balance").map(SavingsTransactionResponse(txn, _))
                         case (Some(_), Some(_))                                                   =>
                           IO.pure(Left(s"Account is not a savings account: ${dto.accountId.value}"))
                         case (None, _)                                                            =>
                           IO.pure(Left("No current period found"))
                         case (_, None)                                                            =>
                           IO.pure(Left(s"Savings account not found: ${dto.accountId.value}"))
                       }
    } yield result
  }

  private def deleteSavingsTransaction(repos: Repositories)(id: SavingsTransactionId): Result[Account] = {
    for {
      txnOpt <- repos.savingsTransactions.findById(id)
      result <- txnOpt match {
                  case Some(txn) =>
                    val now = Instant.now()
                    for {
                      _       <- repos.savingsTransactions.delete(id)
                      updated <- repos.accounts.adjustBalance(txn.accountId, -txn.amount, now)
                    } yield updated.toRight(s"Account not found: ${txn.accountId.value}")
                  case None      =>
                    IO.pure(Left(s"Savings transaction not found: ${id.value}"))
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
