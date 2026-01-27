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

  def currentPeriod: Signal[Option[Period]]
  def totalBalancePLN: Signal[Long]
  def plannedExpenses: Signal[List[BudgetItemDefinition]]
  def estimatedExpenses: Signal[List[BudgetItemDefinition]]
  def plannedIncomes: Signal[List[BudgetItemDefinition]]
  def currentPeriodRecords: Signal[List[ExpenseRecord]]

  def unpaidPlannedExpensesCents: Signal[Long]
  def scaledEstimatedExpensesCents: Signal[Long]
  def pendingIncomeCents: Signal[Long]
  def predictedExpensesCents: Signal[Long]
  def freeMoneyCents: Signal[Long]    // balance - predicted expenses + pending income
  def availableNowCents: Signal[Long] // balance - unpaid planned only (conservative estimate)
  def dailyBudgetCents: Signal[Long]
  def daysRemainingInPeriod: Signal[Int]
}

object DataService {
  val instance: DataService = InMemoryDataService
}
