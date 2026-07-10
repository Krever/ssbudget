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

    val create: Secured[CreateAccount, Account] =
      secureEndpoint.post
        .in("accounts")
        .in(jsonBody[CreateAccount])
        .out(jsonBody[Account])
        .errorOut(stringBody)

    val update: Secured[(AccountId, UpdateAccount), Account] =
      secureEndpoint.put
        .in("accounts" / path[AccountId]("id"))
        .in(jsonBody[UpdateAccount])
        .out(jsonBody[Account])
        .errorOut(stringBody)

    val updateBalance: Secured[(AccountId, UpdateAccountBalance), Account] =
      secureEndpoint.put
        .in("accounts" / path[AccountId]("id") / "balance")
        .in(jsonBody[UpdateAccountBalance])
        .out(jsonBody[Account])
        .errorOut(stringBody)

    val delete: Secured[AccountId, Unit] =
      secureEndpoint.delete
        .in("accounts" / path[AccountId]("id"))
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

  object savingsTransactions {
    val listCurrent: Secured[Unit, List[SavingsTransaction]] =
      secureEndpoint.get
        .in("savings-transactions" / "current")
        .out(jsonBody[List[SavingsTransaction]])
        .errorOut(stringBody)

    val create: Secured[CreateSavingsTransaction, SavingsTransaction] =
      secureEndpoint.post
        .in("savings-transactions")
        .in(jsonBody[CreateSavingsTransaction])
        .out(jsonBody[SavingsTransaction])
        .errorOut(stringBody)

    val delete: Secured[SavingsTransactionId, Unit] =
      secureEndpoint.delete
        .in("savings-transactions" / path[SavingsTransactionId]("id"))
        .errorOut(stringBody)
  }

  object banking {
    val listAspsps: Secured[Option[String], List[Aspsp]] =
      secureEndpoint.get
        .in("banking" / "aspsps")
        .in(query[Option[String]]("country"))
        .out(jsonBody[List[Aspsp]])
        .errorOut(stringBody)

    val connect: Secured[ConnectBankRequest, ConnectBankResponse] =
      secureEndpoint.post
        .in("banking" / "connect")
        .in(jsonBody[ConnectBankRequest])
        .out(jsonBody[ConnectBankResponse])
        .errorOut(stringBody)

    val callback: Secured[BankCallbackRequest, BankConnectionView] =
      secureEndpoint.post
        .in("banking" / "callback")
        .in(jsonBody[BankCallbackRequest])
        .out(jsonBody[BankConnectionView])
        .errorOut(stringBody)

    val connections: Secured[Unit, List[BankConnectionView]] =
      secureEndpoint.get
        .in("banking" / "connections")
        .out(jsonBody[List[BankConnectionView]])
        .errorOut(stringBody)

    val disconnect: Secured[BankConnectionId, Unit] =
      secureEndpoint.delete
        .in("banking" / "connections" / path[BankConnectionId]("id"))
        .errorOut(stringBody)

    val linkAccount: Secured[(BankAccountLinkId, LinkAccountRequest), List[BankConnectionView]] =
      secureEndpoint.post
        .in("banking" / "links" / path[BankAccountLinkId]("linkId") / "account")
        .in(jsonBody[LinkAccountRequest])
        .out(jsonBody[List[BankConnectionView]])
        .errorOut(stringBody)

    val sync: Secured[BankConnectionId, List[BankConnectionView]] =
      secureEndpoint.post
        .in("banking" / "connections" / path[BankConnectionId]("id") / "sync")
        .out(jsonBody[List[BankConnectionView]])
        .errorOut(stringBody)

    val listCardGroups: Secured[Unit, List[CardGroup]] =
      secureEndpoint.get.in("banking" / "card-groups").out(jsonBody[List[CardGroup]]).errorOut(stringBody)

    val createCardGroup: Secured[CreateCardGroup, CardGroup] =
      secureEndpoint.post.in("banking" / "card-groups").in(jsonBody[CreateCardGroup]).out(jsonBody[CardGroup]).errorOut(stringBody)

    val deleteCardGroup: Secured[CardGroupId, Unit] =
      secureEndpoint.delete.in("banking" / "card-groups" / path[CardGroupId]("id")).errorOut(stringBody)

    val linkCardGroup: Secured[(CardGroupId, LinkCardGroupRequest), List[CardGroup]] =
      secureEndpoint.post
        .in("banking" / "card-groups" / path[CardGroupId]("id") / "account")
        .in(jsonBody[LinkCardGroupRequest])
        .out(jsonBody[List[CardGroup]])
        .errorOut(stringBody)

    val importTransactions: Secured[(BankConnectionId, ImportTransactionsRequest), ImportResult] =
      secureEndpoint.post
        .in("banking" / "connections" / path[BankConnectionId]("id") / "import-transactions")
        .in(jsonBody[ImportTransactionsRequest])
        .out(jsonBody[ImportResult])
        .errorOut(stringBody)
  }

  object transactions {
    // Filtering/sorting/capping happen server-side: the browser can't hold thousands of rows. `category` = "all" | "uncategorized" | a categoryId.
    val list: Secured[
      (Option[String], Option[String], Option[String], Option[Boolean], Option[String], Option[Boolean], Option[Int]),
      TransactionListResponse,
    ] =
      secureEndpoint.get
        .in("transactions")
        .in(query[Option[String]]("accountUid"))
        .in(query[Option[String]]("month"))
        .in(query[Option[String]]("category"))
        .in(query[Option[Boolean]]("hideInternal"))
        .in(query[Option[String]]("sort"))
        .in(query[Option[Boolean]]("asc"))
        .in(query[Option[Int]]("limit"))
        .out(jsonBody[TransactionListResponse])
        .errorOut(stringBody)

    val months: Secured[Unit, List[String]] =
      secureEndpoint.get
        .in("transactions" / "months")
        .out(jsonBody[List[String]])
        .errorOut(stringBody)

    val setCategory: Secured[(BankTransactionId, SetCategoryRequest), BankTransaction] =
      secureEndpoint.post
        .in("transactions" / path[BankTransactionId]("id") / "category")
        .in(jsonBody[SetCategoryRequest])
        .out(jsonBody[BankTransaction])
        .errorOut(stringBody)
  }

  object categories {
    val list: Secured[Unit, List[Category]] =
      secureEndpoint.get.in("categories").out(jsonBody[List[Category]]).errorOut(stringBody)

    val summaries: Secured[Unit, List[CategorySummary]] =
      secureEndpoint.get.in("categories" / "summaries").out(jsonBody[List[CategorySummary]]).errorOut(stringBody)

    val create: Secured[CreateCategory, Category] =
      secureEndpoint.post.in("categories").in(jsonBody[CreateCategory]).out(jsonBody[Category]).errorOut(stringBody)

    val update: Secured[(CategoryId, UpdateCategory), Category] =
      secureEndpoint.put.in("categories" / path[CategoryId]("id")).in(jsonBody[UpdateCategory]).out(jsonBody[Category]).errorOut(stringBody)

    val delete: Secured[CategoryId, Unit] =
      secureEndpoint.delete.in("categories" / path[CategoryId]("id")).errorOut(stringBody)
  }

  object rules {
    val list: Secured[Unit, List[ClassificationRule]] =
      secureEndpoint.get.in("rules").out(jsonBody[List[ClassificationRule]]).errorOut(stringBody)

    val create: Secured[CreateRuleRequest, ClassificationRule] =
      secureEndpoint.post.in("rules").in(jsonBody[CreateRuleRequest]).out(jsonBody[ClassificationRule]).errorOut(stringBody)

    val update: Secured[(ClassificationRuleId, UpdateRuleRequest), ClassificationRule] =
      secureEndpoint.put
        .in("rules" / path[ClassificationRuleId]("id"))
        .in(jsonBody[UpdateRuleRequest])
        .out(jsonBody[ClassificationRule])
        .errorOut(stringBody)

    val delete: Secured[ClassificationRuleId, Unit] =
      secureEndpoint.delete.in("rules" / path[ClassificationRuleId]("id")).errorOut(stringBody)

    val reorder: Secured[ReorderRulesRequest, List[ClassificationRule]] =
      secureEndpoint.post.in("rules" / "reorder").in(jsonBody[ReorderRulesRequest]).out(jsonBody[List[ClassificationRule]]).errorOut(stringBody)

    val apply: Secured[Unit, ApplyRulesResult] =
      secureEndpoint.post.in("rules" / "apply").out(jsonBody[ApplyRulesResult]).errorOut(stringBody)

    val preview: Secured[RulePreviewRequest, RulePreviewResponse] =
      secureEndpoint.post.in("rules" / "preview").in(jsonBody[RulePreviewRequest]).out(jsonBody[RulePreviewResponse]).errorOut(stringBody)

    val exportRules: Secured[Unit, RulesExport] =
      secureEndpoint.get.in("rules" / "export").out(jsonBody[RulesExport]).errorOut(stringBody)

    val importRules: Secured[ImportRulesRequest, ImportRulesResult] =
      secureEndpoint.post.in("rules" / "import").in(jsonBody[ImportRulesRequest]).out(jsonBody[ImportRulesResult]).errorOut(stringBody)
  }

  object analytics {
    // `months` = window size in calendar months for the per-category breakdown (default applied server-side).
    val overview: Secured[Option[Int], AnalyticsResponse] =
      secureEndpoint.get
        .in("analytics" / "overview")
        .in(query[Option[Int]]("months"))
        .out(jsonBody[AnalyticsResponse])
        .errorOut(stringBody)
  }

  object oneTimeExpenses {
    val list: Secured[Unit, List[OneTimeExpense]] =
      secureEndpoint.get
        .in("one-time-expenses")
        .out(jsonBody[List[OneTimeExpense]])
        .errorOut(stringBody)

    val create: Secured[CreateOneTimeExpense, OneTimeExpense] =
      secureEndpoint.post
        .in("one-time-expenses")
        .in(jsonBody[CreateOneTimeExpense])
        .out(jsonBody[OneTimeExpense])
        .errorOut(stringBody)

    val update: Secured[(OneTimeExpenseId, UpdateOneTimeExpense), OneTimeExpense] =
      secureEndpoint.put
        .in("one-time-expenses" / path[OneTimeExpenseId]("id"))
        .in(jsonBody[UpdateOneTimeExpense])
        .out(jsonBody[OneTimeExpense])
        .errorOut(stringBody)

    val delete: Secured[OneTimeExpenseId, Unit] =
      secureEndpoint.delete
        .in("one-time-expenses" / path[OneTimeExpenseId]("id"))
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

  object database {
    val download: Secured[Unit, (String, Array[Byte])] =
      secureEndpoint.get
        .in("database" / "export")
        .out(header[String]("Content-Disposition"))
        .out(byteArrayBody)
        .errorOut(stringBody)

    val `import`: Secured[Array[Byte], String] =
      secureEndpoint.post
        .in("database" / "import")
        .in(byteArrayBody)
        .out(stringBody)
        .errorOut(stringBody)
  }

  val all: List[AnyEndpoint] = List(
    accounts.list,
    accounts.create,
    accounts.update,
    accounts.updateBalance,
    accounts.delete,
    budgetItems.list,
    budgetItems.create,
    budgetItems.update,
    budgetItems.delete,
    expenseRecords.listCurrent,
    expenseRecords.pay,
    expenseRecords.unpay,
    periods.list,
    periods.startNew,
    savingsTransactions.listCurrent,
    savingsTransactions.create,
    savingsTransactions.delete,
    oneTimeExpenses.list,
    oneTimeExpenses.create,
    oneTimeExpenses.update,
    oneTimeExpenses.delete,
    exchangeRates.getAll,
    currencies.getSettings,
    currencies.enable,
    currencies.disable,
    currencies.setPrimary,
    currencies.refreshRates,
    banking.listAspsps,
    banking.connect,
    banking.callback,
    banking.connections,
    banking.disconnect,
    banking.linkAccount,
    banking.sync,
    banking.listCardGroups,
    banking.createCardGroup,
    banking.deleteCardGroup,
    banking.linkCardGroup,
    banking.importTransactions,
    transactions.list,
    transactions.months,
    transactions.setCategory,
    categories.list,
    categories.summaries,
    categories.create,
    categories.update,
    categories.delete,
    rules.list,
    rules.create,
    rules.update,
    rules.delete,
    rules.reorder,
    rules.apply,
    rules.preview,
    rules.exportRules,
    rules.importRules,
    analytics.overview,
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

      val create: Client[CreateAccount, Account] =
        baseEndpoint.post.in("accounts").in(jsonBody[CreateAccount]).out(jsonBody[Account]).errorOut(stringBody)

      val update: Client[(AccountId, UpdateAccount), Account] =
        baseEndpoint.put
          .in("accounts" / path[AccountId]("id"))
          .in(jsonBody[UpdateAccount])
          .out(jsonBody[Account])
          .errorOut(stringBody)

      val updateBalance: Client[(AccountId, UpdateAccountBalance), Account] =
        baseEndpoint.put
          .in("accounts" / path[AccountId]("id") / "balance")
          .in(jsonBody[UpdateAccountBalance])
          .out(jsonBody[Account])
          .errorOut(stringBody)

      val delete: Client[AccountId, Unit] =
        baseEndpoint.delete.in("accounts" / path[AccountId]("id")).errorOut(stringBody)
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

    object savingsTransactions {
      val listCurrent: Client[Unit, List[SavingsTransaction]] =
        baseEndpoint.get.in("savings-transactions" / "current").out(jsonBody[List[SavingsTransaction]]).errorOut(stringBody)

      val create: Client[CreateSavingsTransaction, SavingsTransaction] =
        baseEndpoint.post
          .in("savings-transactions")
          .in(jsonBody[CreateSavingsTransaction])
          .out(jsonBody[SavingsTransaction])
          .errorOut(stringBody)

      val delete: Client[SavingsTransactionId, Unit] =
        baseEndpoint.delete.in("savings-transactions" / path[SavingsTransactionId]("id")).errorOut(stringBody)
    }

    object banking {
      val listAspsps: Client[Option[String], List[Aspsp]] =
        baseEndpoint.get
          .in("banking" / "aspsps")
          .in(query[Option[String]]("country"))
          .out(jsonBody[List[Aspsp]])
          .errorOut(stringBody)

      val connect: Client[ConnectBankRequest, ConnectBankResponse] =
        baseEndpoint.post.in("banking" / "connect").in(jsonBody[ConnectBankRequest]).out(jsonBody[ConnectBankResponse]).errorOut(stringBody)

      val callback: Client[BankCallbackRequest, BankConnectionView] =
        baseEndpoint.post.in("banking" / "callback").in(jsonBody[BankCallbackRequest]).out(jsonBody[BankConnectionView]).errorOut(stringBody)

      val connections: Client[Unit, List[BankConnectionView]] =
        baseEndpoint.get.in("banking" / "connections").out(jsonBody[List[BankConnectionView]]).errorOut(stringBody)

      val disconnect: Client[BankConnectionId, Unit] =
        baseEndpoint.delete.in("banking" / "connections" / path[BankConnectionId]("id")).errorOut(stringBody)

      val linkAccount: Client[(BankAccountLinkId, LinkAccountRequest), List[BankConnectionView]] =
        baseEndpoint.post
          .in("banking" / "links" / path[BankAccountLinkId]("linkId") / "account")
          .in(jsonBody[LinkAccountRequest])
          .out(jsonBody[List[BankConnectionView]])
          .errorOut(stringBody)

      val sync: Client[BankConnectionId, List[BankConnectionView]] =
        baseEndpoint.post
          .in("banking" / "connections" / path[BankConnectionId]("id") / "sync")
          .out(jsonBody[List[BankConnectionView]])
          .errorOut(stringBody)

      val listCardGroups: Client[Unit, List[CardGroup]] =
        baseEndpoint.get.in("banking" / "card-groups").out(jsonBody[List[CardGroup]]).errorOut(stringBody)

      val createCardGroup: Client[CreateCardGroup, CardGroup] =
        baseEndpoint.post.in("banking" / "card-groups").in(jsonBody[CreateCardGroup]).out(jsonBody[CardGroup]).errorOut(stringBody)

      val deleteCardGroup: Client[CardGroupId, Unit] =
        baseEndpoint.delete.in("banking" / "card-groups" / path[CardGroupId]("id")).errorOut(stringBody)

      val linkCardGroup: Client[(CardGroupId, LinkCardGroupRequest), List[CardGroup]] =
        baseEndpoint.post
          .in("banking" / "card-groups" / path[CardGroupId]("id") / "account")
          .in(jsonBody[LinkCardGroupRequest])
          .out(jsonBody[List[CardGroup]])
          .errorOut(stringBody)

      val importTransactions: Client[(BankConnectionId, ImportTransactionsRequest), ImportResult] =
        baseEndpoint.post
          .in("banking" / "connections" / path[BankConnectionId]("id") / "import-transactions")
          .in(jsonBody[ImportTransactionsRequest])
          .out(jsonBody[ImportResult])
          .errorOut(stringBody)
    }

    object transactions {
      val list: Client[
        (Option[String], Option[String], Option[String], Option[Boolean], Option[String], Option[Boolean], Option[Int]),
        TransactionListResponse,
      ] =
        baseEndpoint.get
          .in("transactions")
          .in(query[Option[String]]("accountUid"))
          .in(query[Option[String]]("month"))
          .in(query[Option[String]]("category"))
          .in(query[Option[Boolean]]("hideInternal"))
          .in(query[Option[String]]("sort"))
          .in(query[Option[Boolean]]("asc"))
          .in(query[Option[Int]]("limit"))
          .out(jsonBody[TransactionListResponse])
          .errorOut(stringBody)

      val months: Client[Unit, List[String]] =
        baseEndpoint.get.in("transactions" / "months").out(jsonBody[List[String]]).errorOut(stringBody)

      val setCategory: Client[(BankTransactionId, SetCategoryRequest), BankTransaction] =
        baseEndpoint.post
          .in("transactions" / path[BankTransactionId]("id") / "category")
          .in(jsonBody[SetCategoryRequest])
          .out(jsonBody[BankTransaction])
          .errorOut(stringBody)
    }

    object categories {
      val list: Client[Unit, List[Category]] =
        baseEndpoint.get.in("categories").out(jsonBody[List[Category]]).errorOut(stringBody)

      val summaries: Client[Unit, List[CategorySummary]] =
        baseEndpoint.get.in("categories" / "summaries").out(jsonBody[List[CategorySummary]]).errorOut(stringBody)

      val create: Client[CreateCategory, Category] =
        baseEndpoint.post.in("categories").in(jsonBody[CreateCategory]).out(jsonBody[Category]).errorOut(stringBody)

      val update: Client[(CategoryId, UpdateCategory), Category] =
        baseEndpoint.put.in("categories" / path[CategoryId]("id")).in(jsonBody[UpdateCategory]).out(jsonBody[Category]).errorOut(stringBody)

      val delete: Client[CategoryId, Unit] =
        baseEndpoint.delete.in("categories" / path[CategoryId]("id")).errorOut(stringBody)
    }

    object rules {
      val list: Client[Unit, List[ClassificationRule]] =
        baseEndpoint.get.in("rules").out(jsonBody[List[ClassificationRule]]).errorOut(stringBody)

      val create: Client[CreateRuleRequest, ClassificationRule] =
        baseEndpoint.post.in("rules").in(jsonBody[CreateRuleRequest]).out(jsonBody[ClassificationRule]).errorOut(stringBody)

      val update: Client[(ClassificationRuleId, UpdateRuleRequest), ClassificationRule] =
        baseEndpoint.put
          .in("rules" / path[ClassificationRuleId]("id"))
          .in(jsonBody[UpdateRuleRequest])
          .out(jsonBody[ClassificationRule])
          .errorOut(stringBody)

      val delete: Client[ClassificationRuleId, Unit] =
        baseEndpoint.delete.in("rules" / path[ClassificationRuleId]("id")).errorOut(stringBody)

      val reorder: Client[ReorderRulesRequest, List[ClassificationRule]] =
        baseEndpoint.post.in("rules" / "reorder").in(jsonBody[ReorderRulesRequest]).out(jsonBody[List[ClassificationRule]]).errorOut(stringBody)

      val apply: Client[Unit, ApplyRulesResult] =
        baseEndpoint.post.in("rules" / "apply").out(jsonBody[ApplyRulesResult]).errorOut(stringBody)

      val preview: Client[RulePreviewRequest, RulePreviewResponse] =
        baseEndpoint.post.in("rules" / "preview").in(jsonBody[RulePreviewRequest]).out(jsonBody[RulePreviewResponse]).errorOut(stringBody)

      val exportRules: Client[Unit, RulesExport] =
        baseEndpoint.get.in("rules" / "export").out(jsonBody[RulesExport]).errorOut(stringBody)

      val importRules: Client[ImportRulesRequest, ImportRulesResult] =
        baseEndpoint.post.in("rules" / "import").in(jsonBody[ImportRulesRequest]).out(jsonBody[ImportRulesResult]).errorOut(stringBody)
    }

    object analytics {
      val overview: Client[Option[Int], AnalyticsResponse] =
        baseEndpoint.get
          .in("analytics" / "overview")
          .in(query[Option[Int]]("months"))
          .out(jsonBody[AnalyticsResponse])
          .errorOut(stringBody)
    }

    object oneTimeExpenses {
      val list: Client[Unit, List[OneTimeExpense]] =
        baseEndpoint.get.in("one-time-expenses").out(jsonBody[List[OneTimeExpense]]).errorOut(stringBody)

      val create: Client[CreateOneTimeExpense, OneTimeExpense] =
        baseEndpoint.post.in("one-time-expenses").in(jsonBody[CreateOneTimeExpense]).out(jsonBody[OneTimeExpense]).errorOut(stringBody)

      val update: Client[(OneTimeExpenseId, UpdateOneTimeExpense), OneTimeExpense] =
        baseEndpoint.put
          .in("one-time-expenses" / path[OneTimeExpenseId]("id"))
          .in(jsonBody[UpdateOneTimeExpense])
          .out(jsonBody[OneTimeExpense])
          .errorOut(stringBody)

      val delete: Client[OneTimeExpenseId, Unit] =
        baseEndpoint.delete.in("one-time-expenses" / path[OneTimeExpenseId]("id")).errorOut(stringBody)
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

    object database {
      val download: Client[Unit, (String, Array[Byte])] =
        baseEndpoint.get
          .in("database" / "export")
          .out(header[String]("Content-Disposition"))
          .out(byteArrayBody)
          .errorOut(stringBody)

      val `import`: Client[Array[Byte], String] =
        baseEndpoint.post
          .in("database" / "import")
          .in(byteArrayBody)
          .out(stringBody)
          .errorOut(stringBody)
    }
  }
}
