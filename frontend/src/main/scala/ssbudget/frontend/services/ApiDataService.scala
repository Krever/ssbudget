package ssbudget.frontend.services

import com.raquo.laminar.api.L.*
import ssbudget.frontend.services.DataService.sumInPrimary
import ssbudget.shared.api.*
import ssbudget.shared.model.*

import java.time.{Instant, LocalDate}
import scala.concurrent.{ExecutionContext, Future}

class ApiDataService(client: ApiClient)(implicit ec: ExecutionContext) extends DataService {

  // Mutable state for all entities
  private val accountsVar: Var[List[Account]]                     = Var(List.empty)
  private val periodsVar: Var[List[Period]]                       = Var(List.empty)
  private val exchangeRatesVar: Var[Map[Currency, Double]]        = Var(Map.empty)
  private val currencySettingsVar: Var[List[CurrencySetting]]     = Var(List.empty)
  private val availableCurrenciesVar: Var[List[(String, String)]] = Var(List.empty)
  private val categorySummariesVar: Var[List[CategorySummary]]    = Var(List.empty)
  private val investmentMovedVar: Var[Map[AccountId, Long]]       = Var(Map.empty) // net moved into each investment account, primary cents
  private val savingsBaselinesVar: Var[Map[AccountId, Money]]     = Var(Map.empty) // per savings-account balance at period start, in account currency

  private val freeMoneyHistoryVar: Var[Map[PeriodId, List[FreeMoneyPoint]]]  = Var(Map.empty)
  override def freeMoneyHistory: Signal[Map[PeriodId, List[FreeMoneyPoint]]] = freeMoneyHistoryVar.signal

  private def refreshFreeMoneyHistory(): Future[Unit] =
    client.periods
      .freeMoneyHistory()
      .map(history => freeMoneyHistoryVar.set(FreeMoneyTrend.byPeriod(history.snapshots, history.causes)))
      // The chart is an addition to the page. When the history cannot be loaded, the last one stays.
      .recover { case _ => () }

  /** For an edit that changes the plan: when it is done, load the history again, and do not make the caller wait for that. */
  private def replanned(edit: Future[Unit]): Future[Unit] =
    edit.map { _ =>
      refreshFreeMoneyHistory()
      ()
    }

  // Initialize by fetching all data from individual endpoints
  override def initialize(): Future[Unit] = {
    val accountsFut         = client.accounts.list()
    val periodsFut          = client.periods.list()
    val exchangeRatesFut    = client.exchangeRates.getAll()
    val currencySettingsFut = client.currencies.getSettings()
    val categorySummsFut    = client.categories.summaries()
    val baselinesFut        = client.savings.periodBaselines()
    // Part of the first load, not an addition to it: the planned saving is counted down with the latest day of this record.
    val historyFut          = refreshFreeMoneyHistory()

    for {
      accounts         <- accountsFut
      periods          <- periodsFut
      exchangeRates    <- exchangeRatesFut
      currencySettings <- currencySettingsFut
      categorySumms    <- categorySummsFut
      baselines        <- baselinesFut
      _                <- historyFut
    } yield {
      accountsVar.set(accounts)
      periodsVar.set(periods)
      exchangeRatesVar.set(exchangeRates.map(r => r.fromCurrency -> r.rateAsDouble).toMap)
      currencySettingsVar.set(currencySettings.currencies)
      availableCurrenciesVar.set(currencySettings.availableCurrencies.map(c => (c.code, c.name)))
      categorySummariesVar.set(categorySumms)
      savingsBaselinesVar.set(baselines.map(b => b.accountId -> b.baseline).toMap)
      investmentMovedVar.set(baselines.map(b => b.accountId -> b.movedCents).toMap)
    }
  }

  // Raw signals
  override def accounts: Signal[List[Account]]                     = accountsVar.signal
  override def spendingAccounts: Signal[List[Account]]             = accountsVar.signal.map(_.filter(_.role == AccountRole.Spending))
  override def savingsAccounts: Signal[List[Account]]              = accountsVar.signal.map(_.filter(_.role == AccountRole.Savings))
  override def investmentAccounts: Signal[List[Account]]           = accountsVar.signal.map(_.filter(_.role == AccountRole.Investment))
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

  /** Budgeted categories only (any budget type set), in display order (by name). */
  override def budgetedCategories: Signal[List[CategorySummary]] =
    categorySummariesVar.signal.map(_.filter(_.category.budgetType.isDefined).sortBy(_.category.name))

  override def setCategoryBudgetOverride(categoryId: CategoryId, remainingCents: Long): Future[Unit] =
    replanned(client.categories.setOverride(categoryId, SetCategoryOverrideRequest(remainingCents)).map(categorySummariesVar.set))

  override def clearCategoryBudgetOverride(categoryId: CategoryId): Future[Unit] =
    replanned(client.categories.clearOverride(categoryId).map(categorySummariesVar.set))

  override def categoryPeriodTransactions(categoryId: CategoryId, limit: Int): Future[TransactionListResponse] =
    client.transactions.query(
      accountUid = None,
      month = Some(MonthFilter.CurrentPeriod),
      category = Some(categoryId.value),
      hideInternal = true, // the spend behind a category budget excludes own-account transfers, so the drill-down must too
      sort = "date",
      asc = false,
      limit = Some(limit),
      q = None,
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
      q = None,
    )

  override def setTransactionCategory(txId: BankTransactionId, categoryId: Option[CategoryId]): Future[Unit] =
    replanned(
      client.transactions
        .setCategory(txId, SetCategoryRequest(categoryId))
        // The category budgets are derived from categorized spend, so the figures on screen are stale until they're re-read.
        .flatMap(_ => client.categories.summaries().map(categorySummariesVar.set)),
    )

  override def savingsBaselines: Signal[Map[AccountId, Money]] = savingsBaselinesVar.signal
  override def investmentMoved: Signal[Map[AccountId, Long]]   = investmentMovedVar.signal

  // Mutation methods
  private def upsertAccount(account: Account): Unit =
    accountsVar.update(DataService.upsertById(_, account)(_.id))

  override def addAccount(name: String, currency: Currency): Future[Unit] =
    client.accounts.create(CreateAccount(name, currency, AccountRole.Spending)).map(upsertAccount)

  override def deleteAccount(accountId: AccountId): Future[Unit] =
    client.accounts.delete(accountId).map(_ => accountsVar.update(_.filterNot(_.id == accountId)))

  override def updateAccountBalance(accountId: AccountId, amountCents: Long): Future[Unit] =
    client.accounts.updateBalance(accountId, UpdateAccountBalance(amountCents)).map(upsertAccount)

  override def setPeriodExpectedEnd(id: PeriodId, expectedEnd: LocalDate): Future[Unit] =
    replanned(client.periods.setExpectedEnd(id, expectedEnd).map { updated =>
      periodsVar.update(DataService.upsertById(_, updated)(_.id))
    })

  override def setPeriodPlannedSaving(id: PeriodId, plannedSavingCents: Long): Future[Unit] =
    replanned(client.periods.setPlannedSaving(id, plannedSavingCents).map { updated =>
      periodsVar.update(DataService.upsertById(_, updated)(_.id))
    })

  override def startNewPeriod(): Future[Unit] = {
    replanned(client.periods.startNew().map { newPeriod =>
      periodsVar.update { ps =>
        ps.map { p =>
          if p.endDate.isEmpty then p.copy(endDate = Some(Instant.now()))
          else p
        }
      }
      periodsVar.update(_ :+ newPeriod)
    })
  }

  override def addSavingsAccount(name: String, currency: Currency, role: AccountRole): Future[Unit] =
    client.accounts.create(CreateAccount(name, currency, role)).map(upsertAccount)

  override def updateAccount(id: AccountId, name: String, currency: Currency, role: AccountRole): Future[Unit] =
    client.accounts.update(id, UpdateAccount(name, currency, Some(role))).map(upsertAccount)

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
