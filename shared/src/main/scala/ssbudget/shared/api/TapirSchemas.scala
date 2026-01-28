package ssbudget.shared.api

import sttp.tapir.Schema
import ssbudget.shared.model.*

/** Tapir Schema definitions for all model types */
object TapirSchemas {
  // ID types - derive as string
  given Schema[AccountId]            = Schema.string.map[AccountId]((s: String) => Some(AccountId(s)))(_.value)
  given Schema[ExpenseDefId]         = Schema.string.map[ExpenseDefId]((s: String) => Some(ExpenseDefId(s)))(_.value)
  given Schema[PeriodId]             = Schema.string.map[PeriodId]((s: String) => Some(PeriodId(s)))(_.value)
  given Schema[BalanceSnapshotId]    = Schema.string.map[BalanceSnapshotId]((s: String) => Some(BalanceSnapshotId(s)))(_.value)
  given Schema[ExpenseRecordId]      = Schema.string.map[ExpenseRecordId]((s: String) => Some(ExpenseRecordId(s)))(_.value)
  given Schema[SavingsAccountId]     = Schema.string.map[SavingsAccountId]((s: String) => Some(SavingsAccountId(s)))(_.value)
  given Schema[SavingsTransactionId] = Schema.string.map[SavingsTransactionId]((s: String) => Some(SavingsTransactionId(s)))(_.value)

  // Enums
  given Schema[Currency]       = Schema.derivedEnumeration[Currency].defaultStringBased
  given Schema[BudgetItemType] = Schema.derivedEnumeration[BudgetItemType].defaultStringBased
  given Schema[EstimateMode]   = Schema.derivedEnumeration[EstimateMode].defaultStringBased

  // Model types
  given Schema[Account]              = Schema.derived[Account]
  given Schema[BalanceSnapshot]      = Schema.derived[BalanceSnapshot]
  given Schema[BudgetItemDefinition] = Schema.derived[BudgetItemDefinition]
  given Schema[ExpenseRecord]        = Schema.derived[ExpenseRecord]
  given Schema[Period]               = Schema.derived[Period]
  given Schema[SavingsAccount]       = Schema.derived[SavingsAccount]
  given Schema[SavingsTransaction]   = Schema.derived[SavingsTransaction]
  given Schema[ExchangeRate]         = Schema.derived[ExchangeRate]
  given Schema[Money]                = Schema.derived[Money]

  // DTO types
  given Schema[CreateAccount]               = Schema.derived[CreateAccount]
  given Schema[CreateBalanceSnapshot]       = Schema.derived[CreateBalanceSnapshot]
  given Schema[CreateBudgetItem]            = Schema.derived[CreateBudgetItem]
  given Schema[UpdateBudgetItem]            = Schema.derived[UpdateBudgetItem]
  given Schema[PayBudgetItem]               = Schema.derived[PayBudgetItem]
  given Schema[CreateSavingsAccount]        = Schema.derived[CreateSavingsAccount]
  given Schema[UpdateSavingsAccount]        = Schema.derived[UpdateSavingsAccount]
  given Schema[UpdateSavingsAccountBalance] = Schema.derived[UpdateSavingsAccountBalance]
  given Schema[CreateSavingsTransaction]    = Schema.derived[CreateSavingsTransaction]
  given Schema[IdResponse]                  = Schema.derived[IdResponse]
  given Schema[AccountResponse]             = Schema.derived[AccountResponse]
  given Schema[SavingsTransactionResponse]  = Schema.derived[SavingsTransactionResponse]
}
