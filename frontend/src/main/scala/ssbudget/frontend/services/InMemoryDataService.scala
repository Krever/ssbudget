package ssbudget.frontend.services

import com.raquo.laminar.api.L.*
import ssbudget.frontend.services.DataService.sumInPrimary
import ssbudget.shared.api.{CategorySummary, TransactionListResponse}
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
    Account(AccountId(id), name, currency, AccountRole.Spending, cents, BalanceSource.Manual, Some(now))

  private def savings(id: String, name: String, currency: Currency, cents: Long): Account =
    Account(AccountId(id), name, currency, AccountRole.Savings, cents, BalanceSource.Manual, Some(now))

  private val accountsVar: Var[List[Account]] = Var(
    List(
      spending("acc-1", "Main PLN", Currency.PLN, 450000),
      spending("acc-2", "Everyday PLN", Currency.PLN, 1000000),
      spending("acc-3", "Euro Account", Currency.EUR, 50000),
      savings("sav-1", "Emergency Fund", Currency.PLN, 500000),
      savings("sav-2", "Vacation", Currency.EUR, 30000),
      savings("sav-3", "New Laptop", Currency.PLN, 200000),
    ),
  )

  private val budgetItemsVar: Var[List[BudgetItemDefinition]] = Var(
    List(
      // Planned expenses
      BudgetItemDefinition(ExpenseDefId("exp-1"), "Rent", BudgetItemType.PlannedExpense, 250000, Currency.PLN),
      BudgetItemDefinition(ExpenseDefId("exp-2"), "Electricity", BudgetItemType.PlannedExpense, 15000, Currency.PLN),
      BudgetItemDefinition(ExpenseDefId("exp-3"), "Netflix", BudgetItemType.PlannedExpense, 5500, Currency.PLN),
      // Planned incomes
      BudgetItemDefinition(ExpenseDefId("inc-1"), "Freelance Project", BudgetItemType.PlannedIncome, 200000, Currency.PLN),
      BudgetItemDefinition(ExpenseDefId("inc-2"), "Tax Refund", BudgetItemType.PlannedIncome, 50000, Currency.PLN),
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
      // Rent: settled in full. Electricity: part-paid, so the rest still counts as predicted. The others are untouched.
      ExpenseRecord(
        ExpenseRecordId("rec-1"),
        PeriodId("period-1"),
        ExpenseDefId("exp-1"),
        Some(250000),
        Some(tenDaysAgo.plus(1, ChronoUnit.DAYS)),
        settled = true,
      ),
      ExpenseRecord(
        ExpenseRecordId("rec-2"),
        PeriodId("period-1"),
        ExpenseDefId("exp-2"),
        Some(7500),
        Some(tenDaysAgo.plus(2, ChronoUnit.DAYS)),
        settled = false,
      ),
      ExpenseRecord(ExpenseRecordId("rec-3"), PeriodId("period-1"), ExpenseDefId("exp-3"), None, None, settled = false),
      ExpenseRecord(ExpenseRecordId("rec-4"), PeriodId("period-1"), ExpenseDefId("inc-1"), None, None, settled = false),
      ExpenseRecord(ExpenseRecordId("rec-5"), PeriodId("period-1"), ExpenseDefId("inc-2"), None, None, settled = false),
    ),
  )

  private val exchangeRatesVar: Var[Map[Currency, Double]] = Var(
    Map(Currency.EUR -> 4.32),
  )

  private val currencySettingsVar: Var[List[CurrencySetting]] = Var(
    List(
      CurrencySetting(Currency.PLN, "Polish Zloty", isPrimary = true, now),
      CurrencySetting(Currency.EUR, "Euro", isPrimary = false, now),
    ),
  )

  override def accounts: Signal[List[Account]]                     = accountsVar.signal
  override def spendingAccounts: Signal[List[Account]]             = accountsVar.signal.map(_.filter(_.role == AccountRole.Spending))
  override def savingsAccounts: Signal[List[Account]]              = accountsVar.signal.map(_.filter(_.role == AccountRole.Savings))
  override def periods: Signal[List[Period]]                       = periodsVar.signal
  override def exchangeRates: Signal[Map[Currency, Double]]        = exchangeRatesVar.signal
  override def currencySettings: Signal[List[CurrencySetting]]     = currencySettingsVar.signal
  override def availableCurrencies: Signal[List[(String, String)]] = Val(Currency.knownCurrencies)
  override def enabledCurrencies: Signal[List[Currency]]           = currencySettingsVar.signal.map(_.map(_.code))
  override def primaryCurrency: Signal[Currency]                   = currencySettingsVar.signal.map(_.find(_.isPrimary).map(_.code).getOrElse(Currency.PLN))

  override def currentPeriod: Signal[Option[Period]] =
    periodsVar.signal.map(_.find(_.endDate.isEmpty))

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

  override def plannedIncomes: Signal[List[BudgetItemDefinition]] =
    budgetItemsVar.signal.map(_.filter(_.itemType == BudgetItemType.PlannedIncome))

  override def currentPeriodRecords: Signal[List[ExpenseRecord]] =
    Signal
      .combine(budgetRecordsVar.signal, currentPeriod)
      .map { case (records, periodOpt) =>
        periodOpt.fold(List.empty[ExpenseRecord])(period => records.filter(_.periodId == period.id))
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

  // Category budgets are not modelled in the in-memory mock; empty summaries make every derived budget figure zero.
  override def categorySummaries: Signal[List[CategorySummary]]  = Val(List.empty)
  override def budgetedCategories: Signal[List[CategorySummary]] = Val(List.empty)
  override def savingsPeriodChange: Signal[Money]                = primaryCurrency.map(Money.zero)

  override def setCategoryBudgetOverride(categoryId: CategoryId, remainingCents: Long): Future[Unit] = Future.successful(())
  override def clearCategoryBudgetOverride(categoryId: CategoryId): Future[Unit]                     = Future.successful(())

  // The mock has no bank transactions, so a category budget drill-down is always empty here.
  override def categoryPeriodTransactions(categoryId: CategoryId, limit: Int): Future[TransactionListResponse] =
    Future.successful(TransactionListResponse(Nil, 0, Nil))

  override def periodElapsedFraction: Signal[Double] =
    currentPeriod.map {
      case Some(p) =>
        val zone    = ZoneId.of("UTC")
        val start   = p.startDate.atZone(zone).toLocalDate
        val today   = LocalDate.now(zone)
        val day25   = today.withDayOfMonth(25)
        val end     = if today.getDayOfMonth < 25 then day25 else day25.plusMonths(1)
        val total   = ChronoUnit.DAYS.between(start, end).toDouble
        val elapsed = ChronoUnit.DAYS.between(start, today).toDouble
        if total <= 0 then 1.0 else math.max(0.0, math.min(1.0, elapsed / total))
      case None    => 0.0
    }

  private def upsert(account: Account): Unit =
    accountsVar.update(DataService.upsertById(_, account)(_.id))

  override def addAccount(name: String, currency: Currency): Future[Unit] = {
    upsert(spending(s"acc-${System.currentTimeMillis()}", name, currency, 0L))
    Future.successful(())
  }

  override def deleteAccount(accountId: AccountId): Future[Unit] = {
    accountsVar.update(_.filterNot(_.id == accountId))
    Future.successful(())
  }

  override def updateAccountBalance(accountId: AccountId, amountCents: Long): Future[Unit] = {
    accountsVar.update(_.map(a => if a.id == accountId then a.copy(balanceCents = amountCents, balanceUpdatedAt = Some(Instant.now())) else a))
    Future.successful(())
  }

  override def addBudgetItem(name: String, itemType: BudgetItemType, estimateCents: Long, currency: Currency): Future[Unit] = {
    val newId  = ExpenseDefId(s"item-${System.currentTimeMillis()}")
    val newDef = BudgetItemDefinition(newId, name, itemType, estimateCents, currency)
    budgetItemsVar.update(_ :+ newDef)

    getCurrentPeriod.foreach { period =>
      budgetRecordsVar.update { records =>
        records :+ ExpenseRecord(
          ExpenseRecordId(s"rec-${System.currentTimeMillis()}"),
          period.id,
          newId,
          None,
          None,
          settled = false,
        )
      }
    }
    Future.successful(())
  }

  override def updateBudgetItemEstimate(itemId: ExpenseDefId, newEstimateCents: Long, currency: Currency): Future[Unit] = {
    budgetItemsVar.update { defs =>
      defs.map { item =>
        if item.id == itemId then item.copy(estimateCents = newEstimateCents, currency = currency)
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

  override def payBudgetItem(itemId: ExpenseDefId, amountCents: Long, settle: Boolean): Future[Unit] =
    updateCurrentRecord(itemId)(_.withPayment(amountCents, Instant.now(), settle))

  override def resetBudgetItemPayment(itemId: ExpenseDefId): Future[Unit] =
    updateCurrentRecord(itemId)(_.cleared)

  private def updateCurrentRecord(itemId: ExpenseDefId)(f: ExpenseRecord => ExpenseRecord): Future[Unit] = {
    getCurrentPeriod.foreach { period =>
      budgetRecordsVar.update(_.map(rec => if rec.expenseDefId == itemId && rec.periodId == period.id then f(rec) else rec))
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

    budgetRecordsVar.update { records =>
      records ++ budgetItemsVar.now().map { item =>
        ExpenseRecord(
          ExpenseRecordId(s"rec-${System.currentTimeMillis()}-${item.id.value}"),
          newPeriodId,
          item.id,
          None,
          None,
          settled = false,
        )
      }
    }
    Future.successful(())
  }

  private def getCurrentPeriod: Option[Period] =
    periodsVar.now().find(_.endDate.isEmpty)

  override def addSavingsAccount(name: String, currency: Currency): Future[Unit] = {
    upsert(savings(s"sav-${System.currentTimeMillis()}", name, currency, 0L))
    Future.successful(())
  }

  override def updateAccount(id: AccountId, name: String, currency: Currency): Future[Unit] = {
    accountsVar.update(_.map(a => if a.id == id then a.copy(name = name, currency = currency) else a))
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
