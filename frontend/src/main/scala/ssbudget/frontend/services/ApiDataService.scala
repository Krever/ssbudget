package ssbudget.frontend.services

import com.raquo.laminar.api.L.*
import ssbudget.frontend.services.DataService.sumInPrimary
import ssbudget.shared.api.*
import ssbudget.shared.model.*

import java.time.{Instant, LocalDate, ZoneId}
import java.time.temporal.ChronoUnit
import scala.concurrent.{ExecutionContext, Future}

class ApiDataService(client: ApiClient)(implicit ec: ExecutionContext) extends DataService {

  // Mutable state for all entities
  private val accountsVar: Var[List[Account]]                     = Var(List.empty)
  private val budgetItemsVar: Var[List[BudgetItemDefinition]]     = Var(List.empty)
  private val budgetRecordsVar: Var[List[ExpenseRecord]]          = Var(List.empty)
  private val periodsVar: Var[List[Period]]                       = Var(List.empty)
  private val exchangeRatesVar: Var[Map[Currency, Double]]        = Var(Map.empty)
  private val currencySettingsVar: Var[List[CurrencySetting]]     = Var(List.empty)
  private val availableCurrenciesVar: Var[List[(String, String)]] = Var(List.empty)
  private val categorySummariesVar: Var[List[CategorySummary]]    = Var(List.empty)
  private val savingsChangeVar: Var[Money]                        = Var(Money.zero(Currency.PLN)) // actual net savings-balance change this period

  // Initialize by fetching all data from individual endpoints
  override def initialize(): Future[Unit] = {
    val accountsFut         = client.accounts.list()
    val budgetItemsFut      = client.budgetItems.list()
    val periodsFut          = client.periods.list()
    val recordsFut          = client.expenseRecords.listCurrent()
    val exchangeRatesFut    = client.exchangeRates.getAll()
    val currencySettingsFut = client.currencies.getSettings()
    val categorySummsFut    = client.categories.summaries()
    val savingsChangeFut    = client.savings.periodChange()

    for {
      accounts         <- accountsFut
      budgetItems      <- budgetItemsFut
      periods          <- periodsFut
      records          <- recordsFut
      exchangeRates    <- exchangeRatesFut
      currencySettings <- currencySettingsFut
      categorySumms    <- categorySummsFut
      savingsChange    <- savingsChangeFut
    } yield {
      accountsVar.set(accounts)
      budgetItemsVar.set(budgetItems)
      periodsVar.set(periods)
      budgetRecordsVar.set(records)
      exchangeRatesVar.set(exchangeRates.map(r => r.fromCurrency -> r.rateAsDouble).toMap)
      currencySettingsVar.set(currencySettings.currencies)
      availableCurrenciesVar.set(currencySettings.availableCurrencies.map(c => (c.code, c.name)))
      categorySummariesVar.set(categorySumms)
      savingsChangeVar.set(savingsChange)
    }
  }

  // Raw signals
  override def accounts: Signal[List[Account]]                     = accountsVar.signal
  override def spendingAccounts: Signal[List[Account]]             = accountsVar.signal.map(_.filter(_.role == AccountRole.Spending))
  override def savingsAccounts: Signal[List[Account]]              = accountsVar.signal.map(_.filter(_.role == AccountRole.Savings))
  override def periods: Signal[List[Period]]                       = periodsVar.signal
  override def exchangeRates: Signal[Map[Currency, Double]]        = exchangeRatesVar.signal
  override def currencySettings: Signal[List[CurrencySetting]]     = currencySettingsVar.signal
  override def availableCurrencies: Signal[List[(String, String)]] = availableCurrenciesVar.signal
  override def categorySummaries: Signal[List[CategorySummary]]    = categorySummariesVar.signal

  override def enabledCurrencies: Signal[List[Currency]] =
    currencySettingsVar.signal.map(_.map(_.code))

  override def primaryCurrency: Signal[Currency] =
    currencySettingsVar.signal.map(_.find(_.isPrimary).map(_.code).getOrElse(Currency.PLN))

  // Derived signals
  override def currentPeriod: Signal[Option[Period]] =
    periodsVar.signal.map(_.find(_.endDate.isEmpty))

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

  /** Budgeted categories only (any budget type set), in display order (by name). */
  override def budgetedCategories: Signal[List[CategorySummary]] =
    categorySummariesVar.signal.map(_.filter(_.category.budgetType.isDefined).sortBy(_.category.name))

  override def setCategoryBudgetOverride(categoryId: CategoryId, remainingCents: Long): Future[Unit] =
    client.categories.setOverride(categoryId, SetCategoryOverrideRequest(remainingCents)).map(categorySummariesVar.set)

  override def clearCategoryBudgetOverride(categoryId: CategoryId): Future[Unit] =
    client.categories.clearOverride(categoryId).map(categorySummariesVar.set)

  override def categoryPeriodTransactions(categoryId: CategoryId, limit: Int): Future[TransactionListResponse] =
    client.transactions.query(
      accountUid = None,
      month = Some(MonthFilter.CurrentPeriod),
      category = Some(categoryId.value),
      hideInternal = true, // the spend behind a category budget excludes own-account transfers, so the drill-down must too
      sort = "date",
      asc = false,
      limit = Some(limit),
    )

  override def uncategorizedTransactions(limit: Int): Future[TransactionListResponse] =
    client.transactions.query(
      accountUid = None,
      month = None,        // the backlog is whatever was never categorized, whenever it was booked
      category = Some(CategoryFilter.Uncategorized),
      hideInternal = true, // own-account transfers are never categorized, so they aren't a backlog
      sort = "date",
      asc = false,
      limit = Some(limit),
    )

  override def setTransactionCategory(txId: BankTransactionId, categoryId: Option[CategoryId]): Future[Unit] =
    client.transactions
      .setCategory(txId, SetCategoryRequest(categoryId))
      // The category budgets are derived from categorized spend, so the figures on screen are stale until they're re-read.
      .flatMap(_ => client.categories.summaries().map(categorySummariesVar.set))

  override def savingsPeriodChange: Signal[Money] = savingsChangeVar.signal

  // Mutation methods
  private def upsertAccount(account: Account): Unit =
    accountsVar.update(DataService.upsertById(_, account)(_.id))

  override def addAccount(name: String, currency: Currency): Future[Unit] =
    client.accounts.create(CreateAccount(name, currency, AccountRole.Spending)).map(upsertAccount)

  override def deleteAccount(accountId: AccountId): Future[Unit] =
    client.accounts.delete(accountId).map(_ => accountsVar.update(_.filterNot(_.id == accountId)))

  override def updateAccountBalance(accountId: AccountId, amountCents: Long): Future[Unit] =
    client.accounts.updateBalance(accountId, UpdateAccountBalance(amountCents)).map(upsertAccount)

  override def addBudgetItem(name: String, itemType: BudgetItemType, estimateCents: Long, currency: Currency): Future[Unit] = {
    client.budgetItems.create(CreateBudgetItem(name, itemType, estimateCents, currency)).map { item =>
      budgetItemsVar.update(_ :+ item)
      getCurrentPeriod.foreach { period =>
        budgetRecordsVar.update { records =>
          records :+ ExpenseRecord(
            ExpenseRecordId(s"temp-${System.currentTimeMillis()}"),
            period.id,
            item.id,
            None,
            None,
            settled = false,
          )
        }
      }
    }
  }

  override def updateBudgetItemEstimate(itemId: ExpenseDefId, newEstimateCents: Long, currency: Currency): Future[Unit] = {
    val current = budgetItemsVar.now().find(_.id == itemId)
    current match {
      case Some(item) =>
        client.budgetItems.update(itemId, UpdateBudgetItem(item.name, item.itemType, newEstimateCents, currency)).map { updated =>
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

  override def payBudgetItem(itemId: ExpenseDefId, amountCents: Long, settle: Boolean): Future[Unit] = {
    client.expenseRecords.pay(itemId, PayBudgetItem(amountCents, settle)).map { record =>
      budgetRecordsVar.update { records =>
        records.map(r => if r.expenseDefId == itemId && r.periodId == record.periodId then record else r)
      }
    }
  }

  override def resetBudgetItemPayment(itemId: ExpenseDefId): Future[Unit] = {
    client.expenseRecords.unpay(itemId).map { record =>
      budgetRecordsVar.update { records =>
        records.map(r => if r.expenseDefId == itemId && r.periodId == record.periodId then record else r)
      }
    }
  }

  override def startNewPeriod(): Future[Unit] = {
    client.periods.startNew().map { newPeriod =>
      periodsVar.update { ps =>
        ps.map { p =>
          if p.endDate.isEmpty then p.copy(endDate = Some(Instant.now()))
          else p
        }
      }
      periodsVar.update(_ :+ newPeriod)
      budgetRecordsVar.set(
        budgetItemsVar.now().map { item =>
          ExpenseRecord(
            ExpenseRecordId(s"rec-${System.currentTimeMillis()}-${item.id.value}"),
            newPeriod.id,
            item.id,
            None,
            None,
            settled = false,
          )
        },
      )
    }
  }

  override def addSavingsAccount(name: String, currency: Currency): Future[Unit] =
    client.accounts.create(CreateAccount(name, currency, AccountRole.Savings)).map(upsertAccount)

  override def updateAccount(id: AccountId, name: String, currency: Currency): Future[Unit] =
    client.accounts.update(id, UpdateAccount(name, currency)).map(upsertAccount)

  private def getCurrentPeriod: Option[Period] =
    periodsVar.now().find(_.endDate.isEmpty)

  // Currency settings mutations
  override def enableCurrency(code: String): Future[Unit] = {
    client.currencies.enable(code).map { setting =>
      currencySettingsVar.update(_ :+ setting)
    }
  }

  override def disableCurrency(code: String): Future[Unit] = {
    client.currencies.disable(code).map { _ =>
      currencySettingsVar.update(_.filterNot(_.code.code == code))
    }
  }

  override def setPrimaryCurrency(code: String): Future[Unit] = {
    client.currencies.setPrimary(code).map { _ =>
      currencySettingsVar.update { settings =>
        settings.map { s =>
          if s.code.code == code then s.copy(isPrimary = true)
          else s.copy(isPrimary = false)
        }
      }
    }
  }

  override def refreshExchangeRates(): Future[Unit] = {
    client.currencies.refreshRates().flatMap { response =>
      client.exchangeRates.getAll().map { rates =>
        exchangeRatesVar.set(rates.map(r => r.fromCurrency -> r.rateAsDouble).toMap)
      }
    }
  }
}
