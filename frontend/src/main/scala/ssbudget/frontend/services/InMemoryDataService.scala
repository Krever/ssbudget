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

  private def spending(id: String, name: String, currency: Currency, cents: Long): Account =
    Account(AccountId(id), name, currency, AccountRole.Spending, cents, None, BalanceSource.Manual, Some(now))

  private def savings(id: String, name: String, currency: Currency, cents: Long, target: Option[Long]): Account =
    Account(AccountId(id), name, currency, AccountRole.Savings, cents, target, BalanceSource.Manual, Some(now))

  private val accountsVar: Var[List[Account]] = Var(
    List(
      spending("acc-1", "Main PLN", Currency.PLN, 450000),
      spending("acc-2", "Everyday PLN", Currency.PLN, 1000000),
      spending("acc-3", "Euro Account", Currency.EUR, 50000),
      savings("sav-1", "Emergency Fund", Currency.PLN, 500000, Some(50000)),
      savings("sav-2", "Vacation", Currency.EUR, 30000, Some(20000)),
      savings("sav-3", "New Laptop", Currency.PLN, 200000, None),
    ),
  )

  private val budgetItemsVar: Var[List[BudgetItemDefinition]] = Var(
    List(
      // Planned expenses
      BudgetItemDefinition(ExpenseDefId("exp-1"), "Rent", BudgetItemType.PlannedExpense, EstimateMode.Fixed, Some(250000), Currency.PLN),
      BudgetItemDefinition(ExpenseDefId("exp-2"), "Electricity", BudgetItemType.PlannedExpense, EstimateMode.LastMonth, Some(15000), Currency.PLN),
      BudgetItemDefinition(ExpenseDefId("exp-3"), "Netflix", BudgetItemType.PlannedExpense, EstimateMode.Fixed, Some(5500), Currency.PLN),
      // Estimated expenses
      BudgetItemDefinition(ExpenseDefId("exp-4"), "Groceries", BudgetItemType.EstimatedExpense, EstimateMode.Fixed, Some(150000), Currency.PLN),
      BudgetItemDefinition(ExpenseDefId("exp-5"), "Fuel", BudgetItemType.EstimatedExpense, EstimateMode.Average, Some(60000), Currency.PLN),
      BudgetItemDefinition(ExpenseDefId("exp-6"), "Entertainment", BudgetItemType.EstimatedExpense, EstimateMode.Fixed, Some(30000), Currency.PLN),
      // Planned incomes
      BudgetItemDefinition(ExpenseDefId("inc-1"), "Freelance Project", BudgetItemType.PlannedIncome, EstimateMode.Fixed, Some(200000), Currency.PLN),
      BudgetItemDefinition(ExpenseDefId("inc-2"), "Tax Refund", BudgetItemType.PlannedIncome, EstimateMode.Fixed, Some(50000), Currency.PLN),
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
      ExpenseRecord(ExpenseRecordId("rec-1"), PeriodId("period-1"), ExpenseDefId("exp-1"), Some(250000), Some(tenDaysAgo.plus(1, ChronoUnit.DAYS))),
      ExpenseRecord(ExpenseRecordId("rec-2"), PeriodId("period-1"), ExpenseDefId("exp-2"), Some(17500), Some(tenDaysAgo.plus(2, ChronoUnit.DAYS))),
      ExpenseRecord(ExpenseRecordId("rec-3"), PeriodId("period-1"), ExpenseDefId("exp-3"), None, None),
      ExpenseRecord(ExpenseRecordId("rec-4"), PeriodId("period-1"), ExpenseDefId("inc-1"), None, None),
      ExpenseRecord(ExpenseRecordId("rec-5"), PeriodId("period-1"), ExpenseDefId("inc-2"), None, None),
    ),
  )

  private val exchangeRatesVar: Var[Map[Currency, Double]] = Var(
    Map(Currency.EUR -> 4.32),
  )

  private val savingsTransactionsVar: Var[List[SavingsTransaction]] = Var(
    List(
      SavingsTransaction(
        SavingsTransactionId("stxn-1"),
        AccountId("sav-1"),
        PeriodId("period-1"),
        50000,
        Some("Monthly contribution"),
        tenDaysAgo.plus(2, ChronoUnit.DAYS),
      ),
      SavingsTransaction(
        SavingsTransactionId("stxn-2"),
        AccountId("sav-1"),
        PeriodId("period-1"),
        -10000,
        Some("Small emergency"),
        tenDaysAgo.plus(5, ChronoUnit.DAYS),
      ),
      SavingsTransaction(SavingsTransactionId("stxn-3"), AccountId("sav-2"), PeriodId("period-1"), 15000, None, tenDaysAgo.plus(3, ChronoUnit.DAYS)),
    ),
  )

  private val oneTimeExpensesVar: Var[List[OneTimeExpense]] = Var(
    List(
      OneTimeExpense(OneTimeExpenseId("ote-1"), "New Laptop", 450000, Currency.PLN, tenDaysAgo.plus(1, ChronoUnit.DAYS)),
      OneTimeExpense(OneTimeExpenseId("ote-2"), "Car Repair", 120000, Currency.PLN, tenDaysAgo.plus(5, ChronoUnit.DAYS)),
      OneTimeExpense(OneTimeExpenseId("ote-3"), "Concert Tickets", 30000, Currency.EUR, sixtyDaysAgo.plus(10, ChronoUnit.DAYS)),
    ),
  )

  private val currencySettingsVar: Var[List[CurrencySetting]] = Var(
    List(
      CurrencySetting(Currency.PLN, "Polish Zloty", isPrimary = true, now),
      CurrencySetting(Currency.EUR, "Euro", isPrimary = false, now),
    ),
  )

  override def accounts: Signal[List[Account]]                       = accountsVar.signal
  override def spendingAccounts: Signal[List[Account]]               = accountsVar.signal.map(_.filter(_.role == AccountRole.Spending))
  override def savingsAccounts: Signal[List[Account]]                = accountsVar.signal.map(_.filter(_.role == AccountRole.Savings))
  override def budgetItems: Signal[List[BudgetItemDefinition]]       = budgetItemsVar.signal
  override def budgetRecords: Signal[List[ExpenseRecord]]            = budgetRecordsVar.signal
  override def periods: Signal[List[Period]]                         = periodsVar.signal
  override def exchangeRates: Signal[Map[Currency, Double]]          = exchangeRatesVar.signal
  override def savingsTransactions: Signal[List[SavingsTransaction]] = savingsTransactionsVar.signal
  override def oneTimeExpenses: Signal[List[OneTimeExpense]]         = oneTimeExpensesVar.signal
  override def currencySettings: Signal[List[CurrencySetting]]       = currencySettingsVar.signal
  override def availableCurrencies: Signal[List[(String, String)]]   = Val(Currency.knownCurrencies)
  override def enabledCurrencies: Signal[List[Currency]]             = currencySettingsVar.signal.map(_.map(_.code))
  override def primaryCurrency: Signal[Currency]                     = currencySettingsVar.signal.map(_.find(_.isPrimary).map(_.code).getOrElse(Currency.PLN))

  override def currentPeriod: Signal[Option[Period]] =
    periodsVar.signal.map(_.find(_.endDate.isEmpty))

  private def sumInPrimary(amounts: Seq[Money], rates: Map[Currency, Double], primary: Currency): Money = {
    val total = amounts.foldLeft(0L) { (acc, money) =>
      val converted =
        if money.currency == primary then money.amountCents
        else rates.get(money.currency).map(rate => (money.amountCents * rate).toLong).getOrElse(money.amountCents)
      acc + converted
    }
    Money(total, primary)
  }

  override def bankAccountBalance: Signal[Money] =
    spendingAccounts
      .combineWith(exchangeRatesVar.signal)
      .combineWith(primaryCurrency)
      .map { case (accounts, rates, primary) =>
        sumInPrimary(accounts.map(_.balance), rates, primary)
      }

  override def totalBalance: Signal[Money] =
    accountsVar.signal
      .combineWith(exchangeRatesVar.signal)
      .combineWith(primaryCurrency)
      .map { case (accounts, rates, primary) =>
        sumInPrimary(accounts.map(_.balance), rates, primary)
      }

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

  override def unpaidPlannedExpenses: Signal[Money] =
    plannedExpenses
      .combineWith(currentPeriodRecords)
      .combineWith(exchangeRatesVar.signal)
      .combineWith(primaryCurrency)
      .map { case (planned, records, rates, primary) =>
        val unpaidAmounts = planned.flatMap { exp =>
          val isPaid = records.exists(r => r.expenseDefId == exp.id && r.paidAmount.isDefined)
          if isPaid then None else exp.estimateMoney
        }
        sumInPrimary(unpaidAmounts, rates, primary)
      }

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

  override def scaledEstimatedExpenses: Signal[Money] =
    estimatedExpenses
      .combineWith(daysRemainingInPeriod)
      .combineWith(exchangeRatesVar.signal)
      .combineWith(primaryCurrency)
      .map { case (estimated, daysRemaining, rates, primary) =>
        val scaleFactor   = daysRemaining.toDouble / 30.0
        val scaledAmounts = estimated.flatMap { exp =>
          exp.estimateMoney.map(_ * scaleFactor)
        }
        sumInPrimary(scaledAmounts, rates, primary)
      }

  override def currentPeriodSavingsTransactions: Signal[List[SavingsTransaction]] =
    Signal
      .combine(savingsTransactionsVar.signal, currentPeriod)
      .map { case (txns, periodOpt) =>
        periodOpt.fold(List.empty[SavingsTransaction])(period => txns.filter(_.periodId == period.id))
      }

  override def remainingSavingsTarget: Signal[Money] =
    savingsAccounts
      .combineWith(currentPeriodSavingsTransactions)
      .combineWith(exchangeRatesVar.signal)
      .combineWith(primaryCurrency)
      .map { case (accounts, txns, rates, primary) =>
        val remainingAmounts = accounts.flatMap { account =>
          account.savingsTarget.map { target =>
            val contributions = txns.filter(_.accountId == account.id).map(_.amount).sum
            val remaining     = math.max(0L, target - contributions)
            Money(remaining, account.currency)
          }
        }
        sumInPrimary(remainingAmounts, rates, primary)
      }

  override def periodSavingsTotal: Signal[Money] =
    currentPeriodSavingsTransactions
      .combineWith(savingsAccounts)
      .combineWith(exchangeRatesVar.signal)
      .combineWith(primaryCurrency)
      .map { case (txns, accounts, rates, primary) =>
        val accountCurrency = accounts.map(a => a.id -> a.currency).toMap
        val amounts         = txns.map { txn =>
          val currency = accountCurrency.getOrElse(txn.accountId, primary)
          Money(txn.amount, currency)
        }
        sumInPrimary(amounts, rates, primary)
      }

  override def periodOneTimeExpensesTotal: Signal[Money] =
    oneTimeExpensesVar.signal
      .combineWith(currentPeriod)
      .combineWith(exchangeRatesVar.signal)
      .combineWith(primaryCurrency)
      .map { case (expenses, periodOpt, rates, primary) =>
        val zone           = ZoneId.of("UTC")
        val periodExpenses = periodOpt.fold(List.empty[OneTimeExpense]) { period =>
          val periodStartDay = period.startDate.atZone(zone).toLocalDate
          expenses.filter { e =>
            val expDay = e.date.atZone(zone).toLocalDate
            !expDay.isBefore(periodStartDay) &&
            period.endDate.forall(end => expDay.isBefore(end.atZone(zone).toLocalDate))
          }
        }
        val amounts        = periodExpenses.map(e => Money(e.amountCents, e.currency))
        sumInPrimary(amounts, rates, primary)
      }

  override def pendingIncome: Signal[Money] =
    plannedIncomes
      .combineWith(currentPeriodRecords)
      .combineWith(exchangeRatesVar.signal)
      .combineWith(primaryCurrency)
      .map { case (incomes, records, rates, primary) =>
        val pendingAmounts = incomes.flatMap { inc =>
          val isReceived = records.exists(r => r.expenseDefId == inc.id && r.paidAmount.isDefined)
          if isReceived then None else inc.estimateMoney
        }
        sumInPrimary(pendingAmounts, rates, primary)
      }

  override def predictedExpenses: Signal[Money] =
    unpaidPlannedExpenses
      .combineWith(scaledEstimatedExpenses)
      .combineWith(remainingSavingsTarget)
      .map { case (unpaid, scaled, savings) => unpaid + scaled + savings }

  override def freeMoney: Signal[Money] =
    bankAccountBalance
      .combineWith(unpaidPlannedExpenses)
      .combineWith(scaledEstimatedExpenses)
      .combineWith(remainingSavingsTarget)
      .combineWith(pendingIncome)
      .map { case (bankBalance, unpaid, scaled, savings, income) => bankBalance - unpaid - scaled - savings + income }

  override def availableNow: Signal[Money] =
    bankAccountBalance
      .combineWith(unpaidPlannedExpenses)
      .map { case (bankBalance, unpaid) => bankBalance - unpaid }

  override def dailyBudget: Signal[Money] =
    freeMoney
      .combineWith(daysRemainingInPeriod)
      .map { case (free, days) => if days > 0 then free / days else Money.zero(free.currency) }

  private def upsert(account: Account): Unit =
    accountsVar.update(DataService.upsertById(_, account)(_.id))

  override def addAccount(name: String, currency: Currency): Future[Unit] = {
    upsert(spending(s"acc-${System.currentTimeMillis()}", name, currency, 0L))
    Future.successful(())
  }

  override def deleteAccount(accountId: AccountId): Future[Unit] = {
    accountsVar.update(_.filterNot(_.id == accountId))
    savingsTransactionsVar.update(_.filterNot(_.accountId == accountId))
    Future.successful(())
  }

  override def updateAccountBalance(accountId: AccountId, amountCents: Long): Future[Unit] = {
    accountsVar.update(_.map(a => if a.id == accountId then a.copy(balanceCents = amountCents, balanceUpdatedAt = Some(Instant.now())) else a))
    Future.successful(())
  }

  override def addBudgetItem(name: String, itemType: BudgetItemType, estimateCents: Long, currency: Currency): Future[Unit] = {
    val newId  = ExpenseDefId(s"item-${System.currentTimeMillis()}")
    val newDef = BudgetItemDefinition(newId, name, itemType, EstimateMode.Fixed, Some(estimateCents), currency)
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

  override def updateBudgetItemEstimate(itemId: ExpenseDefId, newEstimateCents: Long, currency: Currency): Future[Unit] = {
    budgetItemsVar.update { defs =>
      defs.map { item =>
        if item.id == itemId then item.copy(fixedEstimate = Some(newEstimateCents), currency = currency)
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

  override def addSavingsAccount(name: String, currency: Currency, savingsTarget: Option[Long]): Future[Unit] = {
    upsert(savings(s"sav-${System.currentTimeMillis()}", name, currency, 0L, savingsTarget))
    Future.successful(())
  }

  override def updateAccount(id: AccountId, name: String, currency: Currency, savingsTarget: Option[Long]): Future[Unit] = {
    accountsVar.update(_.map(a => if a.id == id then a.copy(name = name, currency = currency, savingsTarget = savingsTarget) else a))
    Future.successful(())
  }

  override def addSavingsTransaction(accountId: AccountId, amount: Long, note: Option[String]): Future[Unit] = {
    getCurrentPeriod.foreach { period =>
      val txnId = SavingsTransactionId(s"stxn-${System.currentTimeMillis()}")
      val txn   = SavingsTransaction(txnId, accountId, period.id, amount, note, Instant.now())
      savingsTransactionsVar.update(_ :+ txn)
      accountsVar.update(_.map(a => if a.id == accountId then a.copy(balanceCents = a.balanceCents + amount) else a))
    }
    Future.successful(())
  }

  override def deleteSavingsTransaction(id: SavingsTransactionId): Future[Unit] = {
    val txnOpt = savingsTransactionsVar.now().find(_.id == id)
    txnOpt.foreach { txn =>
      accountsVar.update(_.map(a => if a.id == txn.accountId then a.copy(balanceCents = a.balanceCents - txn.amount) else a))
      savingsTransactionsVar.update(_.filterNot(_.id == id))
    }
    Future.successful(())
  }

  override def addOneTimeExpense(name: String, amountCents: Long, currency: Currency, date: Option[Instant]): Future[Unit] = {
    val newId   = OneTimeExpenseId(s"ote-${System.currentTimeMillis()}")
    val expense = OneTimeExpense(newId, name, amountCents, currency, date.getOrElse(Instant.now()))
    oneTimeExpensesVar.update(_ :+ expense)
    Future.successful(())
  }

  override def updateOneTimeExpense(id: OneTimeExpenseId, name: String, amountCents: Long, currency: Currency, date: Instant): Future[Unit] = {
    oneTimeExpensesVar.update { exps =>
      exps.map { e =>
        if e.id == id then e.copy(name = name, amountCents = amountCents, currency = currency, date = date)
        else e
      }
    }
    Future.successful(())
  }

  override def deleteOneTimeExpense(id: OneTimeExpenseId): Future[Unit] = {
    oneTimeExpensesVar.update(_.filterNot(_.id == id))
    Future.successful(())
  }

  override def enableCurrency(code: String): Future[Unit] = {
    val name    = Currency.nameFor(code).getOrElse(code)
    val setting = CurrencySetting(Currency(code), name, isPrimary = false, Instant.now())
    currencySettingsVar.update(_ :+ setting)
    Future.successful(())
  }

  override def disableCurrency(code: String): Future[Unit] = {
    currencySettingsVar.update(_.filterNot(_.code.code == code))
    Future.successful(())
  }

  override def setPrimaryCurrency(code: String): Future[Unit] = {
    currencySettingsVar.update { settings =>
      settings.map { s =>
        if s.code.code == code then s.copy(isPrimary = true)
        else s.copy(isPrimary = false)
      }
    }
    Future.successful(())
  }

  override def refreshExchangeRates(): Future[Unit] =
    Future.successful(())
}
