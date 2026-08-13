package ssbudget.frontend.services

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import ssbudget.shared.api.{CategorySummary, TransactionListResponse}
import ssbudget.shared.model.*

import scala.concurrent.Future

trait DataService {
  // Initialization (for API-backed implementations)
  def initialize(): Future[Unit]

  // Accounts (spending + savings, unified)
  def accounts: Signal[List[Account]]                                             // all accounts
  def spendingAccounts: Signal[List[Account]]                                     // role == Spending
  def addAccount(name: String, currency: Currency): Future[Unit]
  def deleteAccount(accountId: AccountId): Future[Unit]
  def updateAccountBalance(accountId: AccountId, amountCents: Long): Future[Unit] // manual-source accounts only

  // Budget items
  def addBudgetItem(name: String, itemType: BudgetItemType, estimateCents: Long, currency: Currency): Future[Unit]
  def updateBudgetItemEstimate(itemId: ExpenseDefId, newEstimateCents: Long, currency: Currency): Future[Unit]
  def deleteBudgetItem(itemId: ExpenseDefId): Future[Unit]

  /** Record a payment against a planned item this period. `amountCents` ADDS to what's already paid; `settle = true` closes the item, `false` leaves
    * the remainder outstanding.
    */
  def payBudgetItem(itemId: ExpenseDefId, amountCents: Long, settle: Boolean): Future[Unit]

  /** Undo all payment progress for the item this period. */
  def resetBudgetItemPayment(itemId: ExpenseDefId): Future[Unit]

  // Periods
  def periods: Signal[List[Period]]
  def startNewPeriod(): Future[Unit]

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

  // Savings accounts (role == Savings). Buckets with a balance only — intended saving is modelled as a planned expense.
  def savingsAccounts: Signal[List[Account]]
  def addSavingsAccount(name: String, currency: Currency): Future[Unit]
  def updateAccount(id: AccountId, name: String, currency: Currency): Future[Unit]

  // Balance at period start per savings account, in the account's own currency (see AccountPeriodBaseline); no recorded history -> no entry.
  def savingsBaselines: Signal[Map[AccountId, Money]]

  // Derived signals
  def currentPeriod: Signal[Option[Period]]
  def plannedExpenses: Signal[List[BudgetItemDefinition]]
  def plannedIncomes: Signal[List[BudgetItemDefinition]]
  def currentPeriodRecords: Signal[List[ExpenseRecord]]

  // Category budgets (rolling 3-month average per category, computed server-side)
  def categorySummaries: Signal[List[CategorySummary]]
  def budgetedCategories: Signal[List[CategorySummary]] // only categories flagged as monthly budgets
  def periodElapsedFraction: Signal[Double]             // 0..1 through the current period (for pace markers)

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
  def daysRemainingInPeriod: Signal[Int]

  /** Σ of what's still expected across `items`: per item, its estimate minus what's already been paid this period, zero once settled. Concrete on the
    * trait so the real service and the mock can't disagree about the rule that drives free money.
    */
  private def remainingOn(items: Signal[List[BudgetItemDefinition]]): Signal[Money] =
    items
      .combineWith(currentPeriodRecords)
      .combineWith(exchangeRates)
      .combineWith(primaryCurrency)
      .map { case (items, records, rates, primary) =>
        val amounts = items.map { item =>
          val record = records.find(_.expenseDefId == item.id)
          Money(ExpenseRecord.remainingFor(record, item.estimateCents), item.currency)
        }
        DataService.sumInPrimary(amounts, rates, primary)
      }

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

  /** Σ remaining per planned expense — what still has to come out of the balance before the next paycheck. */
  final def unpaidPlannedExpenses: Signal[Money] = remainingOn(plannedExpenses)

  /** Σ remaining per planned income — what is still expected to come in. */
  final def pendingIncome: Signal[Money] = remainingOn(plannedIncomes)

  /** Signed remaining per category budget, in the primary currency: positive is still to be spent, negative is still expected to arrive (see
    * [[CategorySummary.remainingCents]]). Concrete here so the real service and the mock can't disagree about what feeds free money.
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

  /** Σ of what category budgets still expect to SPEND (income budgets excluded), as a positive amount. */
  final def categoryBudgetsToSpend: Signal[Money] = sumBudgets(_ > 0, 1)

  /** Σ of what category budgets still expect to RECEIVE (income budgets only), as a positive amount. */
  final def categoryBudgetsToReceive: Signal[Money] = sumBudgets(_ < 0, -1)

  /** Everything the plan still expects to leave the balance before the next paycheck — hand-declared items and category budgets together. */
  final def stillToPay: Signal[Money] =
    unpaidPlannedExpenses.combineWith(categoryBudgetsToSpend).map { case (planned, budgets) => planned + budgets }

  /** Everything the plan still expects to arrive before the next paycheck, from both kinds of entry. */
  final def stillToReceive: Signal[Money] =
    pendingIncome.combineWith(categoryBudgetsToReceive).map { case (planned, budgets) => planned + budgets }

  /** What's actually free to spend before the next paycheck: the spendable balance, plus what's still expected in, less what's still expected out.
    *
    * Savings are deliberately NOT subtracted: moving money to a savings bucket already lowers the (spending-only) [[bankAccountBalance]], so
    * reserving it again would double-count. Actual savings movement is surfaced separately via [[savingsPeriodChange]] (informational).
    *
    * Concrete here, over the same two totals the Dashboard spells out, so the headline figure and the breakdown explaining it cannot drift apart.
    */
  final def freeMoney: Signal[Money] =
    bankAccountBalance
      .combineWith(stillToReceive)
      .combineWith(stillToPay)
      .map { case (balance, toReceive, toPay) => balance + toReceive - toPay }

  /** A deliberately conservative variant for the shared summary: the balance less only what's planned by hand. */
  final def availableNow: Signal[Money] =
    bankAccountBalance.combineWith(unpaidPlannedExpenses).map { case (balance, unpaid) => balance - unpaid }

  final def dailyBudget: Signal[Money] =
    freeMoney.combineWith(daysRemainingInPeriod).map { case (free, days) => if days > 0 then free / days else Money.zero(free.currency) }
}

object DataService {
  import scala.concurrent.ExecutionContext.Implicits.global

  private lazy val apiService = new ApiDataService(new ApiClient())

  lazy val instance: DataService = {
    if dom.window.location.search.contains("mock=true") then InMemoryDataService
    else apiService
  }

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
