package ssbudget.frontend.services

import com.raquo.laminar.api.L.*
import ssbudget.shared.api.{CategorySummary, TransactionListResponse}
import ssbudget.shared.model.*

import java.time.{LocalDate, ZoneOffset}
import scala.concurrent.Future

trait DataService {
  // Loads all data from the server.
  def initialize(): Future[Unit]

  // Accounts (spending + savings, unified)
  def accounts: Signal[List[Account]]                                             // all accounts
  def spendingAccounts: Signal[List[Account]]                                     // role == Spending
  def addAccount(name: String, currency: Currency): Future[Unit]
  def deleteAccount(accountId: AccountId): Future[Unit]
  def updateAccountBalance(accountId: AccountId, amountCents: Long): Future[Unit] // manual-source accounts only

  // Periods
  def periods: Signal[List[Period]]
  def startNewPeriod(): Future[Unit]

  /** Correct when the current period is expected to end. Everything the page shows about a period's length — days left, day N, the progress bar —
    * reads that one date, so this is the only control over any of them.
    */
  def setPeriodExpectedEnd(id: PeriodId, expectedEnd: LocalDate): Future[Unit]

  /** The recorded free-money points of each period, each with the causes of its day (see [[FreeMoneyTrend]]). Empty until the first load, and empty
    * when it cannot be loaded: the pages work without the chart. It is loaded by [[initialize]], and again by each edit in this service that changes
    * the plan, because the server then derives the earlier days of the open period again.
    */
  def freeMoneyHistory: Signal[Map[PeriodId, List[FreeMoneyPoint]]]

  /** Set how much the period plans to move to savings, in the primary currency. It can be changed at any time in the period: when the plan is made
    * smaller, the part that is no longer planned is free again at once.
    */
  def setPeriodPlannedSaving(id: PeriodId, plannedSavingCents: Long): Future[Unit]

  // Exchange rates (currency code -> rate to primary currency)
  def exchangeRates: Signal[Map[Currency, Double]]

  // Currency settings
  def currencySettings: Signal[List[CurrencySetting]]
  def availableCurrencies: Signal[List[(String, String)]] // (code, name) for dropdown
  def enabledCurrencies: Signal[List[Currency]]
  def primaryCurrency: Signal[Currency]
  def enableCurrency(code: String): Future[Unit]
  def disableCurrency(code: String): Future[Unit]
  def setPrimaryCurrency(code: String): Future[Unit]
  def refreshExchangeRates(): Future[Unit]

  // Savings accounts (role == Savings). Buckets with a balance only. Intended saving is one amount for each period (Period.plannedSavingCents).
  def savingsAccounts: Signal[List[Account]]

  // Investment accounts (role == Investment): money at market value. Not spendable and not part of the savings figures.
  def investmentAccounts: Signal[List[Account]]

  /** Adds a savings or an investment account. */
  def addSavingsAccount(name: String, currency: Currency, role: AccountRole): Future[Unit]

  /** `role`: only a change between Savings and Investment is accepted by the server. */
  def updateAccount(id: AccountId, name: String, currency: Currency, role: AccountRole): Future[Unit]

  // Balance at period start per savings and investment account, in the account's own currency (see AccountPeriodBaseline); no recorded history ->
  // no entry.
  def savingsBaselines: Signal[Map[AccountId, Money]]

  // Net amount moved into each investment account in the current period, in primary-currency cents (see AccountPeriodBaseline.movedCents).
  def investmentMoved: Signal[Map[AccountId, Long]]

  // Derived signals
  def currentPeriod: Signal[Option[Period]]

  // Category budgets (each category's monthly figure and period spend, computed server-side — see CategoryBudgetMethod)
  def categorySummaries: Signal[List[CategorySummary]]
  def budgetedCategories: Signal[List[CategorySummary]] // only categories flagged as monthly budgets

  // Manual remaining-amount override for a category budget, current period only (0 = already paid; clearing restores the computed value).
  def setCategoryBudgetOverride(categoryId: CategoryId, remainingCents: Long): Future[Unit]
  def clearCategoryBudgetOverride(categoryId: CategoryId): Future[Unit]

  /** The current period's transactions behind one category's spend, newest first — what a category budget's "spent" number is made of. Fetched on
    * demand (not a Signal) because it's only needed while a budget row is expanded. Excludes internal transfers, matching how the spend is computed.
    *
    * `limit` caps the rows fetched; the response's `total` is the full count regardless, so a caller showing the first few can still say how many
    * more there are without shipping them.
    */
  def categoryPeriodTransactions(categoryId: CategoryId, limit: Int): Future[TransactionListResponse]

  /** The transactions still waiting for a category, newest first — the triage backlog. Whole history, not just this period: an old transaction is no
    * less uncategorized. Internal transfers are excluded, since they're never categorized. As above, `limit` caps the rows while `total` stays the
    * full count.
    */
  def uncategorizedTransactions(limit: Int): Future[TransactionListResponse]

  /** Assign (or clear, when None) a transaction's category. Category spend feeds the budgets, so this also refreshes what they're computed from. */
  def setTransactionCategory(txId: BankTransactionId, categoryId: Option[CategoryId]): Future[Unit]

  def bankAccountBalance: Signal[Money] // only bank accounts, not savings
  def totalBalance: Signal[Money]       // all accounts including savings (AccountsPage bank-accounts card total)

  /** Days until the current period's expected end. 0 on the expected payday itself, negative once the period has overrun without being closed — the
    * overdue state should be visible, not clamped away.
    */
  final def daysRemainingInPeriod: Signal[Int] =
    currentPeriod.map(_.fold(0)(_.daysRemaining(DataService.today)))

  /** 0..1 through the current period (for pace markers), against the same expected end as [[daysRemainingInPeriod]]. Pinned to 1 once overrun. */
  final def periodElapsedFraction: Signal[Double] =
    currentPeriod.map(_.fold(0.0)(_.elapsedFraction(DataService.today)))

  /** Σ of the savings buckets, in the primary currency — the difference between [[bankAccountBalance]] (spendable) and [[totalBalance]]. */
  final def savingsBalance: Signal[Money] =
    savingsAccounts
      .combineWith(exchangeRates)
      .combineWith(primaryCurrency)
      .map { case (accounts, rates, primary) => DataService.sumInPrimary(accounts.map(_.balance), rates, primary) }

  /** Per savings-account balance change this period, in the account's own currency: current balance − [[savingsBaselines]]. Derived from the live
    * accounts signal, so it stays fresh as balances are edited or synced. No baseline → no entry (the change is unknowable, not zero). Informational.
    */
  final def savingsPeriodDeltas: Signal[Map[AccountId, Money]] =
    savingsAccounts
      .combineWith(savingsBaselines)
      .map { case (accounts, baselines) =>
        accounts.flatMap(acc => baselines.get(acc.id).map(b => acc.id -> Money(acc.balanceCents - b.amountCents, acc.currency))).toMap
      }

  /** Net change in savings-account balances this period (+saved / −withdrawn), in the primary currency: Σ of [[savingsPeriodDeltas]]. Informational.
    */
  final def savingsPeriodChange: Signal[Money] =
    savingsPeriodDeltas
      .combineWith(exchangeRates)
      .combineWith(primaryCurrency)
      .map { case (deltas, rates, primary) => DataService.sumInPrimary(deltas.values.toSeq, rates, primary) }

  /** Σ of the investment accounts at their current value, in the primary currency. */
  final def investmentBalance: Signal[Money] =
    investmentAccounts
      .combineWith(exchangeRates)
      .combineWith(primaryCurrency)
      .map { case (accounts, rates, primary) => DataService.sumInPrimary(accounts.map(_.balance), rates, primary) }

  /** Per investment account, its balance change this period in its own currency. As [[savingsPeriodDeltas]]. */
  final def investmentPeriodDeltas: Signal[Map[AccountId, Money]] =
    investmentAccounts
      .combineWith(savingsBaselines)
      .map { case (accounts, baselines) =>
        accounts.flatMap(acc => baselines.get(acc.id).map(b => acc.id -> Money(acc.balanceCents - b.amountCents, acc.currency))).toMap
      }

  /** Balance change of the investment accounts this period, in the primary currency: what was moved plus what the market did. */
  final def investmentPeriodChange: Signal[Money] =
    investmentPeriodDeltas
      .combineWith(exchangeRates)
      .combineWith(primaryCurrency)
      .map { case (deltas, rates, primary) => DataService.sumInPrimary(deltas.values.toSeq, rates, primary) }

  /** Net amount moved into the investment accounts this period (negative = withdrawn), in the primary currency. This part is saving. */
  final def investmentMovedTotal: Signal[Money] =
    investmentAccounts
      .combineWith(investmentMoved)
      .combineWith(primaryCurrency)
      .map { case (accounts, moved, primary) => Money(accounts.map(a => moved.getOrElse(a.id, 0L)).sum, primary) }

  /** What the market did to the investment accounts this period: their balance change less what was moved. Not saving. */
  final def investmentMarketResult: Signal[Money] =
    investmentPeriodChange.combineWith(investmentMovedTotal).map { case (change, moved) =>
      Money(change.amountCents - moved.amountCents, change.currency)
    }

  /** What was saved this period: the change of the savings accounts plus what was moved into the investment accounts. */
  final def savedThisPeriod: Signal[Money] =
    savingsPeriodChange.combineWith(investmentMovedTotal).map { case (change, moved) =>
      Money(change.amountCents + moved.amountCents, change.currency)
    }

  /** Signed remaining per category budget, in the primary currency: positive is still to be spent, negative is still expected to arrive (see
    * [[CategorySummary.remainingCents]]).
    */
  private def budgetRemainings: Signal[List[Money]] =
    categorySummaries
      .combineWith(periodElapsedFraction)
      .map { case (summaries, elapsed) =>
        summaries.flatMap(s => s.category.budgetType.map(_ => Money(s.remainingCents(elapsed), s.currency)))
      }

  private def sumBudgets(select: Long => Boolean, sign: Long): Signal[Money] =
    budgetRemainings
      .combineWith(exchangeRates)
      .combineWith(primaryCurrency)
      .map { case (amounts, rates, primary) =>
        DataService.sumInPrimary(amounts.filter(m => select(m.amountCents)).map(m => Money(m.amountCents * sign, m.currency)), rates, primary)
      }

  /** Everything the plan still expects to leave the balance before the next paycheck: the sum over the expense budgets, as a positive amount. */
  final def stillToPay: Signal[Money] = sumBudgets(_ > 0, 1)

  /** Everything the plan still expects to arrive before the next paycheck: the sum over the income budgets, as a positive amount. */
  final def stillToReceive: Signal[Money] = sumBudgets(_ < 0, -1)

  /** The net amount that went to savings in the current period so far, by the bank ledger: the opposite of the `fromSavings` term of the period's
    * latest recorded day. It is read from [[freeMoneyHistory]], so the page and the daily record count the planned saving down by one figure.
    */
  private def savedCents: Signal[Long] =
    freeMoneyHistory.combineWith(currentPeriod).map { case (history, period) =>
      period.flatMap(p => history.get(p.id)).flatMap(_.lastOption).fold(0L)(-_.fromSavingsCents)
    }

  /** What the period's planned saving takes from free money (see [[Period.savingReserveCents]]). Positive: the part of the plan that is not yet moved
    * to savings. Negative: more than the plan was moved, and the excess is counted as money for the bills still to pay. Zero with no plan.
    */
  final def savingReserve: Signal[Money] =
    currentPeriod.combineWith(savedCents).combineWith(stillToPay).map { case (period, saved, toPay) =>
      Money(period.fold(0L)(_.savingReserveCents(saved, toPay.amountCents)), toPay.currency)
    }

  /** What's actually free to spend before the next paycheck: the spendable balance, plus what's still expected in, less what's still expected out,
    * less what the planned saving holds back. The same four terms as [[DailyBudgetSnapshot.freeMoneyCents]].
    *
    * The savings BALANCE is deliberately not subtracted: moving money to a savings bucket already lowers the (spending-only) [[bankAccountBalance]].
    *
    * Concrete here, over the same totals the Dashboard spells out, so the headline figure and the breakdown explaining it cannot drift apart.
    */
  final def freeMoney: Signal[Money] =
    bankAccountBalance
      .combineWith(stillToReceive)
      .combineWith(stillToPay)
      .combineWith(savingReserve)
      .map { case (balance, toReceive, toPay, reserve) => balance + toReceive - toPay - reserve }

  final def dailyBudget: Signal[Money] =
    freeMoney.combineWith(daysRemainingInPeriod).map { case (free, days) => if days > 0 then free / days else Money.zero(free.currency) }
}

object DataService {
  import scala.concurrent.ExecutionContext.Implicits.global

  lazy val instance: DataService = new ApiDataService(new ApiClient())

  // UTC everywhere: ZoneId.systemDefault() fails silently in Scala.js without the tzdb dependency (see Formatting).
  private val utc = ZoneOffset.UTC

  /** The day every as-of rule is read against in the browser. The rules themselves live on [[Period]], in shared, so the page and the server's daily
    * snapshot read a period the same way — only the day they are asked about differs.
    */
  def today: LocalDate = LocalDate.now(utc)

  /** Replace the element sharing `item`'s key, or append it if none matches. */
  def upsertById[A, K](xs: List[A], item: A)(key: A => K): List[A] =
    if xs.exists(a => key(a) == key(item)) then xs.map(a => if key(a) == key(item) then item else a)
    else xs :+ item

  /** Total `amounts` in `primary`, converting anything else at the latest rate (falling back to 1:1 when a rate is missing). */
  def sumInPrimary(amounts: Seq[Money], rates: Map[Currency, Double], primary: Currency): Money = {
    val total = amounts.foldLeft(0L) { (acc, money) =>
      val converted =
        if money.currency == primary then money.amountCents
        else rates.get(money.currency).map(rate => (money.amountCents * rate).toLong).getOrElse(money.amountCents)
      acc + converted
    }
    Money(total, primary)
  }
}
