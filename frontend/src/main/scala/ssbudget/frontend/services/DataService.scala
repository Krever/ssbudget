package ssbudget.frontend.services

import com.raquo.laminar.api.L.*
import org.scalajs.dom
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
  def budgetItems: Signal[List[BudgetItemDefinition]]
  def budgetRecords: Signal[List[ExpenseRecord]]
  def addBudgetItem(name: String, itemType: BudgetItemType, estimateCents: Long, currency: Currency): Future[Unit]
  def updateBudgetItemEstimate(itemId: ExpenseDefId, newEstimateCents: Long, currency: Currency): Future[Unit]
  def deleteBudgetItem(itemId: ExpenseDefId): Future[Unit]
  def markBudgetItemAsPaid(itemId: ExpenseDefId, amountCents: Long): Future[Unit]
  def unmarkBudgetItemAsPaid(itemId: ExpenseDefId): Future[Unit]

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

  // Savings accounts (role == Savings)
  def savingsAccounts: Signal[List[Account]]
  def savingsTransactions: Signal[List[SavingsTransaction]]
  def currentPeriodSavingsTransactions: Signal[List[SavingsTransaction]]
  def addSavingsAccount(name: String, currency: Currency, savingsTarget: Option[Long]): Future[Unit]
  def updateAccount(id: AccountId, name: String, currency: Currency, savingsTarget: Option[Long]): Future[Unit]
  def addSavingsTransaction(accountId: AccountId, amount: Long, note: Option[String]): Future[Unit]
  def deleteSavingsTransaction(id: SavingsTransactionId): Future[Unit]
  def remainingSavingsTarget: Signal[Money]     // planned - actual contributions for current period
  def periodSavingsTotal: Signal[Money]         // cumulative savings in current period
  def periodOneTimeExpensesTotal: Signal[Money] // cumulative one-time expenses in current period

  // One-time expenses
  def oneTimeExpenses: Signal[List[OneTimeExpense]]
  def addOneTimeExpense(name: String, amountCents: Long, currency: Currency, date: Option[java.time.Instant]): Future[Unit]
  def updateOneTimeExpense(id: OneTimeExpenseId, name: String, amountCents: Long, currency: Currency, date: java.time.Instant): Future[Unit]
  def deleteOneTimeExpense(id: OneTimeExpenseId): Future[Unit]

  // Derived signals
  def currentPeriod: Signal[Option[Period]]
  def plannedExpenses: Signal[List[BudgetItemDefinition]]
  def estimatedExpenses: Signal[List[BudgetItemDefinition]]
  def plannedIncomes: Signal[List[BudgetItemDefinition]]
  def currentPeriodRecords: Signal[List[ExpenseRecord]]

  def unpaidPlannedExpenses: Signal[Money]
  def scaledEstimatedExpenses: Signal[Money]
  def pendingIncome: Signal[Money]
  def predictedExpenses: Signal[Money]
  def freeMoney: Signal[Money]          // bankAccountBalance - predicted expenses - remaining savings + pending income
  def availableNow: Signal[Money]       // bankAccountBalance - unpaid planned only (conservative estimate)
  def dailyBudget: Signal[Money]
  def bankAccountBalance: Signal[Money] // only bank accounts, not savings
  def totalBalance: Signal[Money]       // all accounts including savings (for accounts table footer)
  def daysRemainingInPeriod: Signal[Int]
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
}
