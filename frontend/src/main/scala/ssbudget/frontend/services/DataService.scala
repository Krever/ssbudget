package ssbudget.frontend.services

import com.raquo.laminar.api.L.*
import ssbudget.shared.model.*

trait DataService {
  def accounts: Signal[List[Account]]
  def balanceSnapshots: Signal[List[BalanceSnapshot]]
  def addAccount(name: String, currency: Currency): Unit
  def updateAccountBalance(accountId: AccountId, amountCents: Long): Unit

  def budgetItems: Signal[List[BudgetItemDefinition]]
  def budgetRecords: Signal[List[ExpenseRecord]]
  def addBudgetItem(name: String, itemType: BudgetItemType, estimateCents: Long): Unit
  def updateBudgetItemEstimate(itemId: ExpenseDefId, newEstimateCents: Long): Unit
  def deleteBudgetItem(itemId: ExpenseDefId): Unit
  def markBudgetItemAsPaid(itemId: ExpenseDefId, amountCents: Long): Unit
  def unmarkBudgetItemAsPaid(itemId: ExpenseDefId): Unit

  def periods: Signal[List[Period]]
  def startNewPeriod(): Unit

  def exchangeRate: Signal[ExchangeRate]

  // Savings accounts
  def savingsAccounts: Signal[List[SavingsAccount]]
  def savingsTransactions: Signal[List[SavingsTransaction]]
  def currentPeriodSavingsTransactions: Signal[List[SavingsTransaction]]
  def addSavingsAccount(name: String, currency: Currency, plannedMonthly: Option[Long]): Unit
  def updateSavingsAccount(id: SavingsAccountId, name: String, currency: Currency, plannedMonthly: Option[Long]): Unit
  def updateSavingsAccountBalance(id: SavingsAccountId, newBalance: Long): Unit
  def deleteSavingsAccount(id: SavingsAccountId): Unit
  def addSavingsTransaction(accountId: SavingsAccountId, amount: Long, note: Option[String]): Unit
  def deleteSavingsTransaction(id: SavingsTransactionId): Unit
  def remainingSavingsTarget: Signal[Money] // planned - actual contributions for current period

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
  val instance: DataService = InMemoryDataService
}
