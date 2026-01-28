package ssbudget.frontend.services

import com.raquo.laminar.api.L.*
import ssbudget.shared.api.*
import ssbudget.shared.model.*

import java.time.{Instant, LocalDate, ZoneId}
import java.time.temporal.ChronoUnit
import scala.concurrent.{ExecutionContext, Future}

class ApiDataService(client: ApiClient)(implicit ec: ExecutionContext) extends DataService {

  // Mutable state for all entities
  private val accountsVar: Var[List[Account]]                       = Var(List.empty)
  private val balanceSnapshotsVar: Var[List[BalanceSnapshot]]       = Var(List.empty)
  private val budgetItemsVar: Var[List[BudgetItemDefinition]]       = Var(List.empty)
  private val budgetRecordsVar: Var[List[ExpenseRecord]]            = Var(List.empty)
  private val periodsVar: Var[List[Period]]                         = Var(List.empty)
  private val exchangeRateVar: Var[ExchangeRate]                    = Var(defaultExchangeRate)
  private val savingsAccountsVar: Var[List[SavingsAccount]]         = Var(List.empty)
  private val savingsTransactionsVar: Var[List[SavingsTransaction]] = Var(List.empty)

  private def defaultExchangeRate: ExchangeRate =
    ExchangeRate.fromDouble(Currency.EUR, Currency.PLN, 4.32, Instant.now())

  // Initialize by fetching all data from individual endpoints
  override def initialize(): Future[Unit] = {
    // Fetch all data in parallel
    val accountsFut        = client.accounts.list()
    val balancesFut        = client.balances.listLatest()
    val budgetItemsFut     = client.budgetItems.list()
    val periodsFut         = client.periods.list()
    val recordsFut         = client.expenseRecords.listCurrent()
    val savingsAccountsFut = client.savingsAccounts.list()
    val savingsTxnsFut     = client.savingsTransactions.listCurrent()
    val exchangeRateFut    = client.exchangeRate.get()

    for {
      accounts        <- accountsFut
      balances        <- balancesFut
      budgetItems     <- budgetItemsFut
      periods         <- periodsFut
      records         <- recordsFut
      savingsAccounts <- savingsAccountsFut
      savingsTxns     <- savingsTxnsFut
      exchangeRate    <- exchangeRateFut
    } yield {
      accountsVar.set(accounts)
      balanceSnapshotsVar.set(balances)
      budgetItemsVar.set(budgetItems)
      periodsVar.set(periods)
      budgetRecordsVar.set(records)
      savingsAccountsVar.set(savingsAccounts)
      savingsTransactionsVar.set(savingsTxns)
      exchangeRateVar.set(exchangeRate.getOrElse(defaultExchangeRate))
    }
  }

  // Raw signals
  override def accounts: Signal[List[Account]]                       = accountsVar.signal
  override def balanceSnapshots: Signal[List[BalanceSnapshot]]       = balanceSnapshotsVar.signal
  override def budgetItems: Signal[List[BudgetItemDefinition]]       = budgetItemsVar.signal
  override def budgetRecords: Signal[List[ExpenseRecord]]            = budgetRecordsVar.signal
  override def periods: Signal[List[Period]]                         = periodsVar.signal
  override def exchangeRate: Signal[ExchangeRate]                    = exchangeRateVar.signal
  override def savingsAccounts: Signal[List[SavingsAccount]]         = savingsAccountsVar.signal
  override def savingsTransactions: Signal[List[SavingsTransaction]] = savingsTransactionsVar.signal

  // Derived signals
  override def currentPeriod: Signal[Option[Period]] =
    periodsVar.signal.map(_.find(_.endDate.isEmpty))

  override def plannedExpenses: Signal[List[BudgetItemDefinition]] =
    budgetItemsVar.signal.map(_.filter(_.itemType == BudgetItemType.PlannedExpense))

  override def estimatedExpenses: Signal[List[BudgetItemDefinition]] =
    budgetItemsVar.signal.map(_.filter(_.itemType == BudgetItemType.EstimatedExpense))

  override def plannedIncomes: Signal[List[BudgetItemDefinition]] =
    budgetItemsVar.signal.map(_.filter(_.itemType == BudgetItemType.PlannedIncome))

  override def currentPeriodRecords: Signal[List[ExpenseRecord]] =
    Signal
      .combine(budgetRecordsVar.signal, currentPeriod)
      .map { case (records, periodOpt) =>
        periodOpt.fold(List.empty[ExpenseRecord])(period => records.filter(_.periodId == period.id))
      }

  override def currentPeriodSavingsTransactions: Signal[List[SavingsTransaction]] =
    Signal
      .combine(savingsTransactionsVar.signal, currentPeriod)
      .map { case (txns, periodOpt) =>
        periodOpt.fold(List.empty[SavingsTransaction])(period => txns.filter(_.periodId == period.id))
      }

  private def totalBalanceCents: Signal[Long] =
    Signal
      .combine(balanceSnapshotsVar.signal, exchangeRateVar.signal)
      .map { case (snapshots, rate) =>
        snapshots.foldLeft(0L) { (acc, snap) =>
          val amountInPLN = snap.currency match {
            case Currency.PLN => snap.amount
            case Currency.EUR => rate.convert(Money(snap.amount, Currency.EUR)).amountCents
          }
          acc + amountInPLN
        }
      }

  override def totalBalance: Signal[Money] = totalBalanceCents.map(Money.pln)

  override def daysRemainingInPeriod: Signal[Int] =
    currentPeriod.map {
      case Some(_) =>
        val today     = LocalDate.now(ZoneId.of("UTC"))
        val day25     = today.withDayOfMonth(25)
        val periodEnd = if today.getDayOfMonth < 25 then day25 else day25.plusMonths(1)
        val daysLeft  = ChronoUnit.DAYS.between(today, periodEnd).toInt
        math.max(1, daysLeft)
      case None    => 0
    }

  private def unpaidPlannedCents: Signal[Long] =
    Signal
      .combine(plannedExpenses, currentPeriodRecords)
      .map { case (planned, records) =>
        planned.foldLeft(0L) { (acc, exp) =>
          val record = records.find(_.expenseDefId == exp.id)
          val isPaid = record.flatMap(_.paidAmount).isDefined
          if isPaid then acc else acc + exp.fixedEstimate.getOrElse(0L)
        }
      }

  override def unpaidPlannedExpenses: Signal[Money] = unpaidPlannedCents.map(Money.pln)

  private def scaledEstimatedCents: Signal[Long] =
    Signal
      .combine(estimatedExpenses, daysRemainingInPeriod)
      .map { case (estimated, daysRemaining) =>
        val scaleFactor = daysRemaining.toDouble / 30.0
        estimated.foldLeft(0L)((acc, exp) => acc + (exp.fixedEstimate.getOrElse(0L) * scaleFactor).toLong)
      }

  override def scaledEstimatedExpenses: Signal[Money] = scaledEstimatedCents.map(Money.pln)

  private def remainingSavingsCents: Signal[Long] =
    Signal
      .combine(savingsAccountsVar.signal, currentPeriodSavingsTransactions)
      .map { case (accounts, txns) =>
        accounts.foldLeft(0L) { (acc, account) =>
          account.plannedMonthly match {
            case Some(target) =>
              val contributions = txns.filter(_.accountId == account.id).map(_.amount).sum
              val remaining     = math.max(0L, target - contributions)
              acc + remaining
            case None         => acc
          }
        }
      }

  override def remainingSavingsTarget: Signal[Money] = remainingSavingsCents.map(Money.pln)

  private def pendingIncomeCents: Signal[Long] =
    Signal
      .combine(plannedIncomes, currentPeriodRecords)
      .map { case (incomes, records) =>
        incomes.foldLeft(0L) { (acc, inc) =>
          val record     = records.find(_.expenseDefId == inc.id)
          val isReceived = record.flatMap(_.paidAmount).isDefined
          if isReceived then acc else acc + inc.fixedEstimate.getOrElse(0L)
        }
      }

  override def pendingIncome: Signal[Money] = pendingIncomeCents.map(Money.pln)

  override def predictedExpenses: Signal[Money] =
    unpaidPlannedCents
      .combineWith(scaledEstimatedCents)
      .combineWith(remainingSavingsCents)
      .map { case (unpaid, scaled, savings) => Money.pln(unpaid + scaled + savings) }

  override def freeMoney: Signal[Money] =
    totalBalanceCents
      .combineWith(unpaidPlannedCents)
      .combineWith(scaledEstimatedCents)
      .combineWith(remainingSavingsCents)
      .combineWith(pendingIncomeCents)
      .map { case (total, unpaid, scaled, savings, income) => Money.pln(total - unpaid - scaled - savings + income) }

  override def availableNow: Signal[Money] =
    Signal
      .combine(totalBalanceCents, unpaidPlannedCents)
      .map { case (total, unpaid) => Money.pln(total - unpaid) }

  override def dailyBudget: Signal[Money] =
    Signal
      .combine(freeMoney, daysRemainingInPeriod)
      .map { case (free, days) => if days > 0 then free / days else Money.pln(0) }

  // Mutation methods
  override def addAccount(name: String, currency: Currency): Future[Unit] = {
    client.accounts.create(CreateAccount(name, currency)).map { response =>
      accountsVar.update(_ :+ response.account)
      balanceSnapshotsVar.update(_ :+ response.balance)
    }
  }

  override def updateAccountBalance(accountId: AccountId, amountCents: Long): Future[Unit] = {
    client.balances.create(CreateBalanceSnapshot(accountId, amountCents)).map { snapshot =>
      balanceSnapshotsVar.update { snapshots =>
        snapshots.filterNot(_.accountId == accountId) :+ snapshot
      }
    }
  }

  override def addBudgetItem(name: String, itemType: BudgetItemType, estimateCents: Long): Future[Unit] = {
    client.budgetItems.create(CreateBudgetItem(name, itemType, estimateCents)).map { item =>
      budgetItemsVar.update(_ :+ item)
      // If it's a planned expense or income, we need to refetch the records for current period
      if itemType == BudgetItemType.PlannedExpense || itemType == BudgetItemType.PlannedIncome then {
        // The backend will create the expense record; we need to refetch via bootstrap
        // For now, optimistically add a pending record
        getCurrentPeriod.foreach { period =>
          budgetRecordsVar.update { records =>
            records :+ ExpenseRecord(
              ExpenseRecordId(s"temp-${System.currentTimeMillis()}"),
              period.id,
              item.id,
              None,
              None,
            )
          }
        }
      }
    }
  }

  override def updateBudgetItemEstimate(itemId: ExpenseDefId, newEstimateCents: Long): Future[Unit] = {
    val current = budgetItemsVar.now().find(_.id == itemId)
    current match {
      case Some(item) =>
        client.budgetItems.update(itemId, UpdateBudgetItem(item.name, item.itemType, newEstimateCents)).map { updated =>
          budgetItemsVar.update(items => items.map(i => if i.id == itemId then updated else i))
        }
      case None       => Future.failed(new Exception(s"Budget item not found: $itemId"))
    }
  }

  override def deleteBudgetItem(itemId: ExpenseDefId): Future[Unit] = {
    client.budgetItems.delete(itemId).map { _ =>
      budgetItemsVar.update(_.filterNot(_.id == itemId))
      budgetRecordsVar.update(_.filterNot(_.expenseDefId == itemId))
    }
  }

  override def markBudgetItemAsPaid(itemId: ExpenseDefId, amountCents: Long): Future[Unit] = {
    client.expenseRecords.pay(itemId, PayBudgetItem(amountCents)).map { record =>
      budgetRecordsVar.update { records =>
        records.map(r => if r.expenseDefId == itemId && r.periodId == record.periodId then record else r)
      }
    }
  }

  override def unmarkBudgetItemAsPaid(itemId: ExpenseDefId): Future[Unit] = {
    client.expenseRecords.unpay(itemId).map { record =>
      budgetRecordsVar.update { records =>
        records.map(r => if r.expenseDefId == itemId && r.periodId == record.periodId then record else r)
      }
    }
  }

  override def startNewPeriod(): Future[Unit] = {
    client.periods.startNew().map { newPeriod =>
      // Close current period locally
      periodsVar.update { ps =>
        ps.map { p =>
          if p.endDate.isEmpty then p.copy(endDate = Some(Instant.now()))
          else p
        }
      }
      // Add new period
      periodsVar.update(_ :+ newPeriod)
      // Reset current period records (they will be fetched from bootstrap if needed)
      // For now, just create empty records for all planned items
      val plannedItems =
        budgetItemsVar.now().filter(item => item.itemType == BudgetItemType.PlannedExpense || item.itemType == BudgetItemType.PlannedIncome)
      budgetRecordsVar.set(
        plannedItems.map { item =>
          ExpenseRecord(
            ExpenseRecordId(s"rec-${System.currentTimeMillis()}-${item.id.value}"),
            newPeriod.id,
            item.id,
            None,
            None,
          )
        },
      )
      // Clear savings transactions for the new period
      savingsTransactionsVar.set(List.empty)
    }
  }

  override def addSavingsAccount(name: String, currency: Currency, plannedMonthly: Option[Long]): Future[Unit] = {
    client.savingsAccounts.create(CreateSavingsAccount(name, currency, plannedMonthly)).map { account =>
      savingsAccountsVar.update(_ :+ account)
    }
  }

  override def updateSavingsAccount(
      id: SavingsAccountId,
      name: String,
      currency: Currency,
      plannedMonthly: Option[Long],
  ): Future[Unit] = {
    client.savingsAccounts.update(id, UpdateSavingsAccount(name, currency, plannedMonthly)).map { updated =>
      savingsAccountsVar.update(accs => accs.map(a => if a.id == id then updated else a))
    }
  }

  override def updateSavingsAccountBalance(id: SavingsAccountId, newBalance: Long): Future[Unit] = {
    client.savingsAccounts.updateBalance(id, UpdateSavingsAccountBalance(newBalance)).map { updated =>
      savingsAccountsVar.update(accs => accs.map(a => if a.id == id then updated else a))
    }
  }

  override def deleteSavingsAccount(id: SavingsAccountId): Future[Unit] = {
    client.savingsAccounts.delete(id).map { _ =>
      savingsAccountsVar.update(_.filterNot(_.id == id))
      savingsTransactionsVar.update(_.filterNot(_.accountId == id))
    }
  }

  override def addSavingsTransaction(accountId: SavingsAccountId, amount: Long, note: Option[String]): Future[Unit] = {
    client.savingsTransactions.create(CreateSavingsTransaction(accountId, amount, note)).map { response =>
      savingsTransactionsVar.update(_ :+ response.transaction)
      savingsAccountsVar.update(accs => accs.map(a => if a.id == accountId then response.updatedAccount else a))
    }
  }

  override def deleteSavingsTransaction(id: SavingsTransactionId): Future[Unit] = {
    val txnOpt = savingsTransactionsVar.now().find(_.id == id)
    txnOpt match {
      case Some(txn) =>
        client.savingsTransactions.delete(id).map { updatedAccount =>
          savingsTransactionsVar.update(_.filterNot(_.id == id))
          savingsAccountsVar.update(accs => accs.map(a => if a.id == txn.accountId then updatedAccount else a))
        }
      case None      => Future.successful(())
    }
  }

  private def getCurrentPeriod: Option[Period] =
    periodsVar.now().find(_.endDate.isEmpty)
}
