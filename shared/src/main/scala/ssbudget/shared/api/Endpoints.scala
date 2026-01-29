package ssbudget.shared.api

import sttp.tapir.*
import sttp.tapir.json.circe.*
import ssbudget.shared.model.*
import ssbudget.shared.api.TapirCodecs.given
import ssbudget.shared.api.TapirSchemas.given

object Endpoints {

  /** Secured endpoint type with optional session cookie, string error, and any effect. */
  type Secured[I, O] = Endpoint[Option[String], I, String, O, Any]

  /** Client endpoint type without security input (browser sends cookies automatically). */
  type Client[I, O] = Endpoint[Unit, I, String, O, Any]

  private val baseEndpoint = endpoint.in("api")

  // All data endpoints require authentication
  private val secureEndpoint = baseEndpoint.securityIn(AuthEndpoints.sessionCookie)

  object accounts {
    val list: Secured[Unit, List[Account]] =
      secureEndpoint.get
        .in("accounts")
        .out(jsonBody[List[Account]])
        .errorOut(stringBody)

    val create: Secured[CreateAccount, AccountResponse] =
      secureEndpoint.post
        .in("accounts")
        .in(jsonBody[CreateAccount])
        .out(jsonBody[AccountResponse])
        .errorOut(stringBody)
  }

  object balances {
    val listLatest: Secured[Unit, List[BalanceSnapshot]] =
      secureEndpoint.get
        .in("balance-snapshots" / "latest")
        .out(jsonBody[List[BalanceSnapshot]])
        .errorOut(stringBody)

    val create: Secured[CreateBalanceSnapshot, BalanceSnapshot] =
      secureEndpoint.post
        .in("balance-snapshots")
        .in(jsonBody[CreateBalanceSnapshot])
        .out(jsonBody[BalanceSnapshot])
        .errorOut(stringBody)
  }

  object budgetItems {
    val list: Secured[Unit, List[BudgetItemDefinition]] =
      secureEndpoint.get
        .in("budget-items")
        .out(jsonBody[List[BudgetItemDefinition]])
        .errorOut(stringBody)

    val create: Secured[CreateBudgetItem, BudgetItemDefinition] =
      secureEndpoint.post
        .in("budget-items")
        .in(jsonBody[CreateBudgetItem])
        .out(jsonBody[BudgetItemDefinition])
        .errorOut(stringBody)

    val update: Secured[(ExpenseDefId, UpdateBudgetItem), BudgetItemDefinition] =
      secureEndpoint.put
        .in("budget-items" / path[ExpenseDefId]("id"))
        .in(jsonBody[UpdateBudgetItem])
        .out(jsonBody[BudgetItemDefinition])
        .errorOut(stringBody)

    val delete: Secured[ExpenseDefId, Unit] =
      secureEndpoint.delete
        .in("budget-items" / path[ExpenseDefId]("id"))
        .errorOut(stringBody)
  }

  object expenseRecords {
    val listCurrent: Secured[Unit, List[ExpenseRecord]] =
      secureEndpoint.get
        .in("expense-records" / "current")
        .out(jsonBody[List[ExpenseRecord]])
        .errorOut(stringBody)

    val pay: Secured[(ExpenseDefId, PayBudgetItem), ExpenseRecord] =
      secureEndpoint.post
        .in("expense-records" / path[ExpenseDefId]("expenseDefId") / "pay")
        .in(jsonBody[PayBudgetItem])
        .out(jsonBody[ExpenseRecord])
        .errorOut(stringBody)

    val unpay: Secured[ExpenseDefId, ExpenseRecord] =
      secureEndpoint.post
        .in("expense-records" / path[ExpenseDefId]("expenseDefId") / "unpay")
        .out(jsonBody[ExpenseRecord])
        .errorOut(stringBody)
  }

  object periods {
    val list: Secured[Unit, List[Period]] =
      secureEndpoint.get
        .in("periods")
        .out(jsonBody[List[Period]])
        .errorOut(stringBody)

    val startNew: Secured[Unit, Period] =
      secureEndpoint.post
        .in("periods" / "start")
        .out(jsonBody[Period])
        .errorOut(stringBody)
  }

  object savingsAccounts {
    val list: Secured[Unit, List[SavingsAccount]] =
      secureEndpoint.get
        .in("savings-accounts")
        .out(jsonBody[List[SavingsAccount]])
        .errorOut(stringBody)

    val create: Secured[CreateSavingsAccount, SavingsAccount] =
      secureEndpoint.post
        .in("savings-accounts")
        .in(jsonBody[CreateSavingsAccount])
        .out(jsonBody[SavingsAccount])
        .errorOut(stringBody)

    val update: Secured[(SavingsAccountId, UpdateSavingsAccount), SavingsAccount] =
      secureEndpoint.put
        .in("savings-accounts" / path[SavingsAccountId]("id"))
        .in(jsonBody[UpdateSavingsAccount])
        .out(jsonBody[SavingsAccount])
        .errorOut(stringBody)

    val updateBalance: Secured[(SavingsAccountId, UpdateSavingsAccountBalance), SavingsAccount] =
      secureEndpoint.put
        .in("savings-accounts" / path[SavingsAccountId]("id") / "balance")
        .in(jsonBody[UpdateSavingsAccountBalance])
        .out(jsonBody[SavingsAccount])
        .errorOut(stringBody)

    val delete: Secured[SavingsAccountId, Unit] =
      secureEndpoint.delete
        .in("savings-accounts" / path[SavingsAccountId]("id"))
        .errorOut(stringBody)
  }

  object savingsTransactions {
    val listCurrent: Secured[Unit, List[SavingsTransaction]] =
      secureEndpoint.get
        .in("savings-transactions" / "current")
        .out(jsonBody[List[SavingsTransaction]])
        .errorOut(stringBody)

    val create: Secured[CreateSavingsTransaction, SavingsTransactionResponse] =
      secureEndpoint.post
        .in("savings-transactions")
        .in(jsonBody[CreateSavingsTransaction])
        .out(jsonBody[SavingsTransactionResponse])
        .errorOut(stringBody)

    val delete: Secured[SavingsTransactionId, SavingsAccount] =
      secureEndpoint.delete
        .in("savings-transactions" / path[SavingsTransactionId]("id"))
        .out(jsonBody[SavingsAccount])
        .errorOut(stringBody)
  }

  object exchangeRates {
    val getAll: Secured[Unit, List[ExchangeRate]] =
      secureEndpoint.get
        .in("exchange-rates")
        .out(jsonBody[List[ExchangeRate]])
        .errorOut(stringBody)
  }

  object currencies {
    val getSettings: Secured[Unit, CurrencySettingsResponse] =
      secureEndpoint.get
        .in("currencies")
        .out(jsonBody[CurrencySettingsResponse])
        .errorOut(stringBody)

    val enable: Secured[EnableCurrencyRequest, CurrencySetting] =
      secureEndpoint.post
        .in("currencies")
        .in(jsonBody[EnableCurrencyRequest])
        .out(jsonBody[CurrencySetting])
        .errorOut(stringBody)

    val disable: Secured[String, Unit] =
      secureEndpoint.delete
        .in("currencies" / path[String]("code"))
        .errorOut(stringBody)

    val setPrimary: Secured[SetPrimaryCurrencyRequest, Unit] =
      secureEndpoint.post
        .in("currencies" / "primary")
        .in(jsonBody[SetPrimaryCurrencyRequest])
        .errorOut(stringBody)

    val refreshRates: Secured[Unit, ExchangeRatesResponse] =
      secureEndpoint.post
        .in("currencies" / "refresh-rates")
        .out(jsonBody[ExchangeRatesResponse])
        .errorOut(stringBody)
  }

  object test {
    // Test reset endpoint - still needs to be protected in non-test mode
    val reset: Secured[Unit, Unit] =
      secureEndpoint.post
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
    exchangeRates.getAll,
    currencies.getSettings,
    currencies.enable,
    currencies.disable,
    currencies.setPrimary,
    currencies.refreshRates,
    test.reset,
  )

  /** Client-side endpoint definitions for browser use.
    *
    * These don't have securityIn because the browser automatically sends cookies with the request. The server still validates the session cookie via
    * Tapir's security input.
    */
  object client {
    object accounts {
      val list: Client[Unit, List[Account]] =
        baseEndpoint.get.in("accounts").out(jsonBody[List[Account]]).errorOut(stringBody)

      val create: Client[CreateAccount, AccountResponse] =
        baseEndpoint.post.in("accounts").in(jsonBody[CreateAccount]).out(jsonBody[AccountResponse]).errorOut(stringBody)
    }

    object balances {
      val listLatest: Client[Unit, List[BalanceSnapshot]] =
        baseEndpoint.get.in("balance-snapshots" / "latest").out(jsonBody[List[BalanceSnapshot]]).errorOut(stringBody)

      val create: Client[CreateBalanceSnapshot, BalanceSnapshot] =
        baseEndpoint.post.in("balance-snapshots").in(jsonBody[CreateBalanceSnapshot]).out(jsonBody[BalanceSnapshot]).errorOut(stringBody)
    }

    object budgetItems {
      val list: Client[Unit, List[BudgetItemDefinition]] =
        baseEndpoint.get.in("budget-items").out(jsonBody[List[BudgetItemDefinition]]).errorOut(stringBody)

      val create: Client[CreateBudgetItem, BudgetItemDefinition] =
        baseEndpoint.post.in("budget-items").in(jsonBody[CreateBudgetItem]).out(jsonBody[BudgetItemDefinition]).errorOut(stringBody)

      val update: Client[(ExpenseDefId, UpdateBudgetItem), BudgetItemDefinition] =
        baseEndpoint.put
          .in("budget-items" / path[ExpenseDefId]("id"))
          .in(jsonBody[UpdateBudgetItem])
          .out(jsonBody[BudgetItemDefinition])
          .errorOut(stringBody)

      val delete: Client[ExpenseDefId, Unit] =
        baseEndpoint.delete.in("budget-items" / path[ExpenseDefId]("id")).errorOut(stringBody)
    }

    object expenseRecords {
      val listCurrent: Client[Unit, List[ExpenseRecord]] =
        baseEndpoint.get.in("expense-records" / "current").out(jsonBody[List[ExpenseRecord]]).errorOut(stringBody)

      val pay: Client[(ExpenseDefId, PayBudgetItem), ExpenseRecord] =
        baseEndpoint.post
          .in("expense-records" / path[ExpenseDefId]("expenseDefId") / "pay")
          .in(jsonBody[PayBudgetItem])
          .out(jsonBody[ExpenseRecord])
          .errorOut(stringBody)

      val unpay: Client[ExpenseDefId, ExpenseRecord] =
        baseEndpoint.post.in("expense-records" / path[ExpenseDefId]("expenseDefId") / "unpay").out(jsonBody[ExpenseRecord]).errorOut(stringBody)
    }

    object periods {
      val list: Client[Unit, List[Period]] =
        baseEndpoint.get.in("periods").out(jsonBody[List[Period]]).errorOut(stringBody)

      val startNew: Client[Unit, Period] =
        baseEndpoint.post.in("periods" / "start").out(jsonBody[Period]).errorOut(stringBody)
    }

    object savingsAccounts {
      val list: Client[Unit, List[SavingsAccount]] =
        baseEndpoint.get.in("savings-accounts").out(jsonBody[List[SavingsAccount]]).errorOut(stringBody)

      val create: Client[CreateSavingsAccount, SavingsAccount] =
        baseEndpoint.post.in("savings-accounts").in(jsonBody[CreateSavingsAccount]).out(jsonBody[SavingsAccount]).errorOut(stringBody)

      val update: Client[(SavingsAccountId, UpdateSavingsAccount), SavingsAccount] =
        baseEndpoint.put
          .in("savings-accounts" / path[SavingsAccountId]("id"))
          .in(jsonBody[UpdateSavingsAccount])
          .out(jsonBody[SavingsAccount])
          .errorOut(stringBody)

      val updateBalance: Client[(SavingsAccountId, UpdateSavingsAccountBalance), SavingsAccount] =
        baseEndpoint.put
          .in("savings-accounts" / path[SavingsAccountId]("id") / "balance")
          .in(jsonBody[UpdateSavingsAccountBalance])
          .out(jsonBody[SavingsAccount])
          .errorOut(stringBody)

      val delete: Client[SavingsAccountId, Unit] =
        baseEndpoint.delete.in("savings-accounts" / path[SavingsAccountId]("id")).errorOut(stringBody)
    }

    object savingsTransactions {
      val listCurrent: Client[Unit, List[SavingsTransaction]] =
        baseEndpoint.get.in("savings-transactions" / "current").out(jsonBody[List[SavingsTransaction]]).errorOut(stringBody)

      val create: Client[CreateSavingsTransaction, SavingsTransactionResponse] =
        baseEndpoint.post
          .in("savings-transactions")
          .in(jsonBody[CreateSavingsTransaction])
          .out(jsonBody[SavingsTransactionResponse])
          .errorOut(stringBody)

      val delete: Client[SavingsTransactionId, SavingsAccount] =
        baseEndpoint.delete.in("savings-transactions" / path[SavingsTransactionId]("id")).out(jsonBody[SavingsAccount]).errorOut(stringBody)
    }

    object exchangeRates {
      val getAll: Client[Unit, List[ExchangeRate]] =
        baseEndpoint.get.in("exchange-rates").out(jsonBody[List[ExchangeRate]]).errorOut(stringBody)
    }

    object currencies {
      val getSettings: Client[Unit, CurrencySettingsResponse] =
        baseEndpoint.get.in("currencies").out(jsonBody[CurrencySettingsResponse]).errorOut(stringBody)

      val enable: Client[EnableCurrencyRequest, CurrencySetting] =
        baseEndpoint.post.in("currencies").in(jsonBody[EnableCurrencyRequest]).out(jsonBody[CurrencySetting]).errorOut(stringBody)

      val disable: Client[String, Unit] =
        baseEndpoint.delete.in("currencies" / path[String]("code")).errorOut(stringBody)

      val setPrimary: Client[SetPrimaryCurrencyRequest, Unit] =
        baseEndpoint.post.in("currencies" / "primary").in(jsonBody[SetPrimaryCurrencyRequest]).errorOut(stringBody)

      val refreshRates: Client[Unit, ExchangeRatesResponse] =
        baseEndpoint.post.in("currencies" / "refresh-rates").out(jsonBody[ExchangeRatesResponse]).errorOut(stringBody)
    }
  }
}
