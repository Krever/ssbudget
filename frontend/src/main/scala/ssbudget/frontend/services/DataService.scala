package ssbudget.frontend.services

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import ssbudget.shared.model.*

import scala.concurrent.Future

trait DataService {
  // Initialization (for API-backed implementations)
  def initialize(): Future[Unit]

  // Accounts
  def accounts: Signal[List[Account]]
  def balanceSnapshots: Signal[List[BalanceSnapshot]]
  def addAccount(name: String, currency: Currency): Future[Unit]
  def deleteAccount(accountId: AccountId): Future[Unit]
  def updateAccountBalance(accountId: AccountId, amountCents: Long): Future[Unit]

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

  // Savings accounts
  def savingsAccounts: Signal[List[SavingsAccount]]
  def savingsTransactions: Signal[List[SavingsTransaction]]
  def currentPeriodSavingsTransactions: Signal[List[SavingsTransaction]]
  def addSavingsAccount(name: String, currency: Currency, plannedMonthly: Option[Long]): Future[Unit]
  def updateSavingsAccount(id: SavingsAccountId, name: String, currency: Currency, plannedMonthly: Option[Long]): Future[Unit]
  def updateSavingsAccountBalance(id: SavingsAccountId, newBalance: Long): Future[Unit]
  def deleteSavingsAccount(id: SavingsAccountId): Future[Unit]
  def addSavingsTransaction(accountId: SavingsAccountId, amount: Long, note: Option[String]): Future[Unit]
  def deleteSavingsTransaction(id: SavingsTransactionId): Future[Unit]
  def remainingSavingsTarget: Signal[Money] // planned - actual contributions for current period

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
  def freeMoney: Signal[Money]    // balance - predicted expenses - remaining savings + pending income
  def availableNow: Signal[Money] // balance - unpaid planned only (conservative estimate)
  def dailyBudget: Signal[Money]
  def totalBalance: Signal[Money]
  def daysRemainingInPeriod: Signal[Int]
}

object DataService {
  import scala.concurrent.ExecutionContext.Implicits.global

  private lazy val apiService = new ApiDataService(new ApiClient())

  lazy val instance: DataService = {
    if dom.window.location.search.contains("mock=true") then InMemoryDataService
    else apiService
  }
}
