package ssbudget.frontend.services

import com.raquo.laminar.api.L.*
import ssbudget.shared.model.*

import java.time.{Instant, LocalDate, ZoneId}
import java.time.temporal.ChronoUnit
import scala.concurrent.Future

object InMemoryDataService extends DataService {

  override def initialize(): Future[Unit] = Future.successful(())

  private val now           = Instant.now()
  private val tenDaysAgo    = now.minus(10, ChronoUnit.DAYS)
  private val thirtyDaysAgo = now.minus(30, ChronoUnit.DAYS)
  private val sixtyDaysAgo  = now.minus(60, ChronoUnit.DAYS)

  private val accountsVar: Var[List[Account]] = Var(
    List(
      Account(AccountId("acc-1"), "Main PLN", Currency.PLN),
      Account(AccountId("acc-2"), "Savings PLN", Currency.PLN),
      Account(AccountId("acc-3"), "Euro Account", Currency.EUR),
    ),
  )

  private val balanceSnapshotsVar: Var[List[BalanceSnapshot]] = Var(
    List(
      BalanceSnapshot(BalanceSnapshotId("snap-1"), AccountId("acc-1"), 450000, Currency.PLN, now),
      BalanceSnapshot(BalanceSnapshotId("snap-2"), AccountId("acc-2"), 1000000, Currency.PLN, now),
      BalanceSnapshot(BalanceSnapshotId("snap-3"), AccountId("acc-3"), 50000, Currency.EUR, now),
    ),
  )

  private val budgetItemsVar: Var[List[BudgetItemDefinition]] = Var(
    List(
      // Planned expenses
      BudgetItemDefinition(ExpenseDefId("exp-1"), "Rent", BudgetItemType.PlannedExpense, EstimateMode.Fixed, Some(250000)),
      BudgetItemDefinition(ExpenseDefId("exp-2"), "Electricity", BudgetItemType.PlannedExpense, EstimateMode.LastMonth, Some(15000)),
      BudgetItemDefinition(ExpenseDefId("exp-3"), "Netflix", BudgetItemType.PlannedExpense, EstimateMode.Fixed, Some(5500)),
      // Estimated expenses
      BudgetItemDefinition(ExpenseDefId("exp-4"), "Groceries", BudgetItemType.EstimatedExpense, EstimateMode.Fixed, Some(150000)),
      BudgetItemDefinition(ExpenseDefId("exp-5"), "Fuel", BudgetItemType.EstimatedExpense, EstimateMode.Average, Some(60000)),
      BudgetItemDefinition(ExpenseDefId("exp-6"), "Entertainment", BudgetItemType.EstimatedExpense, EstimateMode.Fixed, Some(30000)),
      // Planned incomes
      BudgetItemDefinition(ExpenseDefId("inc-1"), "Freelance Project", BudgetItemType.PlannedIncome, EstimateMode.Fixed, Some(200000)),
      BudgetItemDefinition(ExpenseDefId("inc-2"), "Tax Refund", BudgetItemType.PlannedIncome, EstimateMode.Fixed, Some(50000)),
    ),
  )

  private val periodsVar: Var[List[Period]] = Var(
    List(
      Period(PeriodId("period-1"), tenDaysAgo, None),
      Period(PeriodId("period-0"), sixtyDaysAgo, Some(thirtyDaysAgo)),
    ),
  )

  private val budgetRecordsVar: Var[List[ExpenseRecord]] = Var(
    List(
      // Expense records
      ExpenseRecord(ExpenseRecordId("rec-1"), PeriodId("period-1"), ExpenseDefId("exp-1"), Some(250000), Some(tenDaysAgo.plus(1, ChronoUnit.DAYS))),
      ExpenseRecord(ExpenseRecordId("rec-2"), PeriodId("period-1"), ExpenseDefId("exp-2"), Some(17500), Some(tenDaysAgo.plus(2, ChronoUnit.DAYS))),
      ExpenseRecord(ExpenseRecordId("rec-3"), PeriodId("period-1"), ExpenseDefId("exp-3"), None, None),
      // Income records (not yet received)
      ExpenseRecord(ExpenseRecordId("rec-4"), PeriodId("period-1"), ExpenseDefId("inc-1"), None, None),
      ExpenseRecord(ExpenseRecordId("rec-5"), PeriodId("period-1"), ExpenseDefId("inc-2"), None, None),
    ),
  )

  private val exchangeRateVar: Var[ExchangeRate] = Var(
    ExchangeRate.fromDouble(Currency.EUR, Currency.PLN, 4.32, now),
  )

  private val savingsAccountsVar: Var[List[SavingsAccount]] = Var(
    List(
      SavingsAccount(SavingsAccountId("sav-1"), "Emergency Fund", Currency.PLN, 500000, Some(50000)),
      SavingsAccount(SavingsAccountId("sav-2"), "Vacation", Currency.EUR, 30000, Some(20000)),
      SavingsAccount(SavingsAccountId("sav-3"), "New Laptop", Currency.PLN, 200000, None),
    ),
  )

  private val savingsTransactionsVar: Var[List[SavingsTransaction]] = Var(
    List(
      SavingsTransaction(
        SavingsTransactionId("stxn-1"),
        SavingsAccountId("sav-1"),
        PeriodId("period-1"),
        50000,
        Some("Monthly contribution"),
        tenDaysAgo.plus(2, ChronoUnit.DAYS),
      ),
      SavingsTransaction(
        SavingsTransactionId("stxn-2"),
        SavingsAccountId("sav-1"),
        PeriodId("period-1"),
        -10000,
        Some("Small emergency"),
        tenDaysAgo.plus(5, ChronoUnit.DAYS),
      ),
      SavingsTransaction(
        SavingsTransactionId("stxn-3"),
        SavingsAccountId("sav-2"),
        PeriodId("period-1"),
        15000,
        None,
        tenDaysAgo.plus(3, ChronoUnit.DAYS),
      ),
    ),
  )

  override def accounts: Signal[List[Account]]                       = accountsVar.signal
  override def balanceSnapshots: Signal[List[BalanceSnapshot]]       = balanceSnapshotsVar.signal
  override def budgetItems: Signal[List[BudgetItemDefinition]]       = budgetItemsVar.signal
  override def budgetRecords: Signal[List[ExpenseRecord]]            = budgetRecordsVar.signal
  override def periods: Signal[List[Period]]                         = periodsVar.signal
  override def exchangeRate: Signal[ExchangeRate]                    = exchangeRateVar.signal
  override def savingsAccounts: Signal[List[SavingsAccount]]         = savingsAccountsVar.signal
  override def savingsTransactions: Signal[List[SavingsTransaction]] = savingsTransactionsVar.signal

  override def currentPeriod: Signal[Option[Period]] =
    periodsVar.signal.map(_.find(_.endDate.isEmpty))

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

  private def scaledEstimatedCents: Signal[Long] =
    Signal
      .combine(estimatedExpenses, daysRemainingInPeriod)
      .map { case (estimated, daysRemaining) =>
        val scaleFactor = daysRemaining.toDouble / 30.0
        estimated.foldLeft(0L)((acc, exp) => acc + (exp.fixedEstimate.getOrElse(0L) * scaleFactor).toLong)
      }

  override def scaledEstimatedExpenses: Signal[Money] = scaledEstimatedCents.map(Money.pln)

  override def currentPeriodSavingsTransactions: Signal[List[SavingsTransaction]] =
    Signal
      .combine(savingsTransactionsVar.signal, currentPeriod)
      .map { case (txns, periodOpt) =>
        periodOpt.fold(List.empty[SavingsTransaction])(period => txns.filter(_.periodId == period.id))
      }

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

  override def addAccount(name: String, currency: Currency): Future[Unit] = {
    val newId = AccountId(s"acc-${System.currentTimeMillis()}")
    accountsVar.update(_ :+ Account(newId, name, currency))
    balanceSnapshotsVar.update { snaps =>
      snaps :+ BalanceSnapshot(
        BalanceSnapshotId(s"snap-${System.currentTimeMillis()}"),
        newId,
        0L,
        currency,
        Instant.now(),
      )
    }
    Future.successful(())
  }

  override def updateAccountBalance(accountId: AccountId, amountCents: Long): Future[Unit] = {
    val account = accountsVar.now().find(_.id == accountId)
    account.foreach { acc =>
      balanceSnapshotsVar.update { snapshots =>
        val newSnapshot = BalanceSnapshot(
          BalanceSnapshotId(s"snap-${System.currentTimeMillis()}"),
          accountId,
          amountCents,
          acc.currency,
          Instant.now(),
        )
        snapshots.filterNot(_.accountId == accountId) :+ newSnapshot
      }
    }
    Future.successful(())
  }

  override def addBudgetItem(name: String, itemType: BudgetItemType, estimateCents: Long): Future[Unit] = {
    val newId  = ExpenseDefId(s"item-${System.currentTimeMillis()}")
    val newDef = BudgetItemDefinition(newId, name, itemType, EstimateMode.Fixed, Some(estimateCents))
    budgetItemsVar.update(_ :+ newDef)

    if itemType == BudgetItemType.PlannedExpense || itemType == BudgetItemType.PlannedIncome then {
      getCurrentPeriod.foreach { period =>
        budgetRecordsVar.update { records =>
          records :+ ExpenseRecord(
            ExpenseRecordId(s"rec-${System.currentTimeMillis()}"),
            period.id,
            newId,
            None,
            None,
          )
        }
      }
    }
    Future.successful(())
  }

  override def updateBudgetItemEstimate(itemId: ExpenseDefId, newEstimateCents: Long): Future[Unit] = {
    budgetItemsVar.update { defs =>
      defs.map { item =>
        if item.id == itemId then item.copy(fixedEstimate = Some(newEstimateCents))
        else item
      }
    }
    Future.successful(())
  }

  override def deleteBudgetItem(itemId: ExpenseDefId): Future[Unit] = {
    budgetItemsVar.update(_.filterNot(_.id == itemId))
    budgetRecordsVar.update(_.filterNot(_.expenseDefId == itemId))
    Future.successful(())
  }

  override def markBudgetItemAsPaid(itemId: ExpenseDefId, amountCents: Long): Future[Unit] = {
    getCurrentPeriod.foreach { period =>
      budgetRecordsVar.update { records =>
        records.map { rec =>
          if rec.expenseDefId == itemId && rec.periodId == period.id then rec.copy(paidAmount = Some(amountCents), paidAt = Some(Instant.now()))
          else rec
        }
      }
    }
    Future.successful(())
  }

  override def unmarkBudgetItemAsPaid(itemId: ExpenseDefId): Future[Unit] = {
    getCurrentPeriod.foreach { period =>
      budgetRecordsVar.update { records =>
        records.map { rec =>
          if rec.expenseDefId == itemId && rec.periodId == period.id then rec.copy(paidAmount = None, paidAt = None)
          else rec
        }
      }
    }
    Future.successful(())
  }

  override def startNewPeriod(): Future[Unit] = {
    val now = Instant.now()

    periodsVar.update { ps =>
      ps.map { p =>
        if p.endDate.isEmpty then p.copy(endDate = Some(now))
        else p
      }
    }

    val newPeriodId = PeriodId(s"period-${System.currentTimeMillis()}")
    periodsVar.update(_ :+ Period(newPeriodId, now, None))

    val plannedItems =
      budgetItemsVar.now().filter(item => item.itemType == BudgetItemType.PlannedExpense || item.itemType == BudgetItemType.PlannedIncome)
    budgetRecordsVar.update { records =>
      records ++ plannedItems.map { item =>
        ExpenseRecord(
          ExpenseRecordId(s"rec-${System.currentTimeMillis()}-${item.id.value}"),
          newPeriodId,
          item.id,
          None,
          None,
        )
      }
    }
    Future.successful(())
  }

  private def getCurrentPeriod: Option[Period] =
    periodsVar.now().find(_.endDate.isEmpty)

  override def addSavingsAccount(name: String, currency: Currency, plannedMonthly: Option[Long]): Future[Unit] = {
    val newId = SavingsAccountId(s"sav-${System.currentTimeMillis()}")
    savingsAccountsVar.update(_ :+ SavingsAccount(newId, name, currency, 0L, plannedMonthly))
    Future.successful(())
  }

  override def updateSavingsAccount(id: SavingsAccountId, name: String, currency: Currency, plannedMonthly: Option[Long]): Future[Unit] = {
    savingsAccountsVar.update { accounts =>
      accounts.map { acc =>
        if acc.id == id then acc.copy(name = name, currency = currency, plannedMonthly = plannedMonthly)
        else acc
      }
    }
    Future.successful(())
  }

  override def updateSavingsAccountBalance(id: SavingsAccountId, newBalance: Long): Future[Unit] = {
    savingsAccountsVar.update { accounts =>
      accounts.map { acc =>
        if acc.id == id then acc.copy(currentBalance = newBalance)
        else acc
      }
    }
    Future.successful(())
  }

  override def deleteSavingsAccount(id: SavingsAccountId): Future[Unit] = {
    savingsAccountsVar.update(_.filterNot(_.id == id))
    savingsTransactionsVar.update(_.filterNot(_.accountId == id))
    Future.successful(())
  }

  override def addSavingsTransaction(accountId: SavingsAccountId, amount: Long, note: Option[String]): Future[Unit] = {
    getCurrentPeriod.foreach { period =>
      val txnId = SavingsTransactionId(s"stxn-${System.currentTimeMillis()}")
      val txn   = SavingsTransaction(txnId, accountId, period.id, amount, note, Instant.now())
      savingsTransactionsVar.update(_ :+ txn)
      // Update the account balance
      savingsAccountsVar.update { accounts =>
        accounts.map { acc =>
          if acc.id == accountId then acc.copy(currentBalance = acc.currentBalance + amount)
          else acc
        }
      }
    }
    Future.successful(())
  }

  override def deleteSavingsTransaction(id: SavingsTransactionId): Future[Unit] = {
    val txnOpt = savingsTransactionsVar.now().find(_.id == id)
    txnOpt.foreach { txn =>
      // Reverse the balance change
      savingsAccountsVar.update { accounts =>
        accounts.map { acc =>
          if acc.id == txn.accountId then acc.copy(currentBalance = acc.currentBalance - txn.amount)
          else acc
        }
      }
      savingsTransactionsVar.update(_.filterNot(_.id == id))
    }
    Future.successful(())
  }
}
