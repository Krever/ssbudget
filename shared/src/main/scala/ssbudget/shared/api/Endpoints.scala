package ssbudget.shared.api

import sttp.tapir.*
import sttp.tapir.json.circe.*
import ssbudget.shared.model.*
import ssbudget.shared.api.TapirCodecs.given
import ssbudget.shared.api.TapirSchemas.given

object Endpoints {

  private val baseEndpoint = endpoint.in("api")

  object accounts {
    val list: Endpoint[Unit, Unit, String, List[Account], Any] =
      baseEndpoint.get
        .in("accounts")
        .out(jsonBody[List[Account]])
        .errorOut(stringBody)

    val create: Endpoint[Unit, CreateAccount, String, AccountResponse, Any] =
      baseEndpoint.post
        .in("accounts")
        .in(jsonBody[CreateAccount])
        .out(jsonBody[AccountResponse])
        .errorOut(stringBody)
  }

  object balances {
    val listLatest: Endpoint[Unit, Unit, String, List[BalanceSnapshot], Any] =
      baseEndpoint.get
        .in("balance-snapshots" / "latest")
        .out(jsonBody[List[BalanceSnapshot]])
        .errorOut(stringBody)

    val create: Endpoint[Unit, CreateBalanceSnapshot, String, BalanceSnapshot, Any] =
      baseEndpoint.post
        .in("balance-snapshots")
        .in(jsonBody[CreateBalanceSnapshot])
        .out(jsonBody[BalanceSnapshot])
        .errorOut(stringBody)
  }

  object budgetItems {
    val list: Endpoint[Unit, Unit, String, List[BudgetItemDefinition], Any] =
      baseEndpoint.get
        .in("budget-items")
        .out(jsonBody[List[BudgetItemDefinition]])
        .errorOut(stringBody)

    val create: Endpoint[Unit, CreateBudgetItem, String, BudgetItemDefinition, Any] =
      baseEndpoint.post
        .in("budget-items")
        .in(jsonBody[CreateBudgetItem])
        .out(jsonBody[BudgetItemDefinition])
        .errorOut(stringBody)

    val update: Endpoint[Unit, (ExpenseDefId, UpdateBudgetItem), String, BudgetItemDefinition, Any] =
      baseEndpoint.put
        .in("budget-items" / path[ExpenseDefId]("id"))
        .in(jsonBody[UpdateBudgetItem])
        .out(jsonBody[BudgetItemDefinition])
        .errorOut(stringBody)

    val delete: Endpoint[Unit, ExpenseDefId, String, Unit, Any] =
      baseEndpoint.delete
        .in("budget-items" / path[ExpenseDefId]("id"))
        .errorOut(stringBody)
  }

  object expenseRecords {
    val listCurrent: Endpoint[Unit, Unit, String, List[ExpenseRecord], Any] =
      baseEndpoint.get
        .in("expense-records" / "current")
        .out(jsonBody[List[ExpenseRecord]])
        .errorOut(stringBody)

    val pay: Endpoint[Unit, (ExpenseDefId, PayBudgetItem), String, ExpenseRecord, Any] =
      baseEndpoint.post
        .in("expense-records" / path[ExpenseDefId]("expenseDefId") / "pay")
        .in(jsonBody[PayBudgetItem])
        .out(jsonBody[ExpenseRecord])
        .errorOut(stringBody)

    val unpay: Endpoint[Unit, ExpenseDefId, String, ExpenseRecord, Any] =
      baseEndpoint.post
        .in("expense-records" / path[ExpenseDefId]("expenseDefId") / "unpay")
        .out(jsonBody[ExpenseRecord])
        .errorOut(stringBody)
  }

  object periods {
    val list: Endpoint[Unit, Unit, String, List[Period], Any] =
      baseEndpoint.get
        .in("periods")
        .out(jsonBody[List[Period]])
        .errorOut(stringBody)

    val startNew: Endpoint[Unit, Unit, String, Period, Any] =
      baseEndpoint.post
        .in("periods" / "start")
        .out(jsonBody[Period])
        .errorOut(stringBody)
  }

  object savingsAccounts {
    val list: Endpoint[Unit, Unit, String, List[SavingsAccount], Any] =
      baseEndpoint.get
        .in("savings-accounts")
        .out(jsonBody[List[SavingsAccount]])
        .errorOut(stringBody)

    val create: Endpoint[Unit, CreateSavingsAccount, String, SavingsAccount, Any] =
      baseEndpoint.post
        .in("savings-accounts")
        .in(jsonBody[CreateSavingsAccount])
        .out(jsonBody[SavingsAccount])
        .errorOut(stringBody)

    val update: Endpoint[Unit, (SavingsAccountId, UpdateSavingsAccount), String, SavingsAccount, Any] =
      baseEndpoint.put
        .in("savings-accounts" / path[SavingsAccountId]("id"))
        .in(jsonBody[UpdateSavingsAccount])
        .out(jsonBody[SavingsAccount])
        .errorOut(stringBody)

    val updateBalance: Endpoint[Unit, (SavingsAccountId, UpdateSavingsAccountBalance), String, SavingsAccount, Any] =
      baseEndpoint.put
        .in("savings-accounts" / path[SavingsAccountId]("id") / "balance")
        .in(jsonBody[UpdateSavingsAccountBalance])
        .out(jsonBody[SavingsAccount])
        .errorOut(stringBody)

    val delete: Endpoint[Unit, SavingsAccountId, String, Unit, Any] =
      baseEndpoint.delete
        .in("savings-accounts" / path[SavingsAccountId]("id"))
        .errorOut(stringBody)
  }

  object savingsTransactions {
    val listCurrent: Endpoint[Unit, Unit, String, List[SavingsTransaction], Any] =
      baseEndpoint.get
        .in("savings-transactions" / "current")
        .out(jsonBody[List[SavingsTransaction]])
        .errorOut(stringBody)

    val create: Endpoint[Unit, CreateSavingsTransaction, String, SavingsTransactionResponse, Any] =
      baseEndpoint.post
        .in("savings-transactions")
        .in(jsonBody[CreateSavingsTransaction])
        .out(jsonBody[SavingsTransactionResponse])
        .errorOut(stringBody)

    val delete: Endpoint[Unit, SavingsTransactionId, String, SavingsAccount, Any] =
      baseEndpoint.delete
        .in("savings-transactions" / path[SavingsTransactionId]("id"))
        .out(jsonBody[SavingsAccount])
        .errorOut(stringBody)
  }

  object exchangeRate {
    val get: Endpoint[Unit, Unit, String, Option[ExchangeRate], Any] =
      baseEndpoint.get
        .in("exchange-rate")
        .out(jsonBody[Option[ExchangeRate]])
        .errorOut(stringBody)
  }

  object test {
    val reset: Endpoint[Unit, Unit, String, Unit, Any] =
      baseEndpoint.post
        .in("test" / "reset")
        .errorOut(stringBody)
  }

  val all: List[AnyEndpoint] = List(
    accounts.list,
    accounts.create,
    balances.listLatest,
    balances.create,
    budgetItems.list,
    budgetItems.create,
    budgetItems.update,
    budgetItems.delete,
    expenseRecords.listCurrent,
    expenseRecords.pay,
    expenseRecords.unpay,
    periods.list,
    periods.startNew,
    savingsAccounts.list,
    savingsAccounts.create,
    savingsAccounts.update,
    savingsAccounts.updateBalance,
    savingsAccounts.delete,
    savingsTransactions.listCurrent,
    savingsTransactions.create,
    savingsTransactions.delete,
    exchangeRate.get,
    test.reset,
  )
}
