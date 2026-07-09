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
  given Schema[SavingsTransactionId] = Schema.string.map[SavingsTransactionId]((s: String) => Some(SavingsTransactionId(s)))(_.value)
  given Schema[OneTimeExpenseId]     = Schema.string.map[OneTimeExpenseId]((s: String) => Some(OneTimeExpenseId(s)))(_.value)
  given Schema[BankConnectionId]     = Schema.string.map[BankConnectionId]((s: String) => Some(BankConnectionId(s)))(_.value)
  given Schema[BankAccountLinkId]    = Schema.string.map[BankAccountLinkId]((s: String) => Some(BankAccountLinkId(s)))(_.value)
  given Schema[CardGroupId]          = Schema.string.map[CardGroupId]((s: String) => Some(CardGroupId(s)))(_.value)

  // Enums and value types
  given Schema[Currency]         = Schema.string.map[Currency]((s: String) => Some(Currency(s)))(_.code)
  given Schema[BudgetItemType]   = Schema.derivedEnumeration[BudgetItemType].defaultStringBased
  given Schema[EstimateMode]     = Schema.derivedEnumeration[EstimateMode].defaultStringBased
  given Schema[ConnectionStatus] = Schema.derivedEnumeration[ConnectionStatus].defaultStringBased
  given Schema[AccountRole]      = Schema.derivedEnumeration[AccountRole].defaultStringBased
  given Schema[BalanceSource]    = Schema.derivedEnumeration[BalanceSource].defaultStringBased
  given Schema[BankLinkTarget]   = Schema.derived[BankLinkTarget]

  // Model types
  given Schema[Account]              = Schema.derived[Account]
  given Schema[BalanceSnapshot]      = Schema.derived[BalanceSnapshot]
  given Schema[BudgetItemDefinition] = Schema.derived[BudgetItemDefinition]
  given Schema[ExpenseRecord]        = Schema.derived[ExpenseRecord]
  given Schema[Period]               = Schema.derived[Period]
  given Schema[SavingsTransaction]   = Schema.derived[SavingsTransaction]
  given Schema[OneTimeExpense]       = Schema.derived[OneTimeExpense]
  given Schema[ExchangeRate]         = Schema.derived[ExchangeRate]
  given Schema[Money]                = Schema.derived[Money]
  given Schema[BankConnection]       = Schema.derived[BankConnection]
  given Schema[BankAccountLink]      = Schema.derived[BankAccountLink]
  given Schema[CardGroup]            = Schema.derived[CardGroup]

  // DTO types
  given Schema[CreateAccount]              = Schema.derived[CreateAccount]
  given Schema[UpdateAccount]              = Schema.derived[UpdateAccount]
  given Schema[UpdateAccountBalance]       = Schema.derived[UpdateAccountBalance]
  given Schema[CreateBudgetItem]           = Schema.derived[CreateBudgetItem]
  given Schema[UpdateBudgetItem]           = Schema.derived[UpdateBudgetItem]
  given Schema[PayBudgetItem]              = Schema.derived[PayBudgetItem]
  given Schema[CreateSavingsTransaction]   = Schema.derived[CreateSavingsTransaction]
  given Schema[IdResponse]                 = Schema.derived[IdResponse]
  given Schema[SavingsTransactionResponse] = Schema.derived[SavingsTransactionResponse]
  given Schema[CreateOneTimeExpense]       = Schema.derived[CreateOneTimeExpense]
  given Schema[UpdateOneTimeExpense]       = Schema.derived[UpdateOneTimeExpense]

  // Banking DTOs
  given Schema[Aspsp]                = Schema.derived[Aspsp]
  given Schema[ConnectBankRequest]   = Schema.derived[ConnectBankRequest]
  given Schema[ConnectBankResponse]  = Schema.derived[ConnectBankResponse]
  given Schema[BankCallbackRequest]  = Schema.derived[BankCallbackRequest]
  given Schema[BankConnectionView]   = Schema.derived[BankConnectionView]
  given Schema[LinkAccountRequest]   = Schema.derived[LinkAccountRequest]
  given Schema[LinkCardGroupRequest] = Schema.derived[LinkCardGroupRequest]
  given Schema[CreateCardGroup]      = Schema.derived[CreateCardGroup]

  // Currency settings DTOs
  given Schema[CurrencySetting]           = Schema.derived[CurrencySetting]
  given Schema[EnableCurrencyRequest]     = Schema.derived[EnableCurrencyRequest]
  given Schema[SetPrimaryCurrencyRequest] = Schema.derived[SetPrimaryCurrencyRequest]
  given Schema[KnownCurrency]             = Schema.derived[KnownCurrency]
  given Schema[CurrencySettingsResponse]  = Schema.derived[CurrencySettingsResponse]
  given Schema[ExchangeRatesResponse]     = Schema.derived[ExchangeRatesResponse]

  // Auth DTO types
  given Schema[AuthStatus]                    = Schema.derived[AuthStatus]
  given Schema[SetupRequest]                  = Schema.derived[SetupRequest]
  given Schema[LoginRequest]                  = Schema.derived[LoginRequest]
  given Schema[PasskeyInfo]                   = Schema.derived[PasskeyInfo]
  given Schema[PasskeyRegisterStartRequest]   = Schema.derived[PasskeyRegisterStartRequest]
  given Schema[PasskeyRegistrationOptions]    = Schema.derived[PasskeyRegistrationOptions]
  given Schema[AuthenticatorSelection]        = Schema.derived[AuthenticatorSelection]
  given Schema[PubKeyCredParam]               = Schema.derived[PubKeyCredParam]
  given Schema[PasskeyRegistrationResponse]   = Schema.derived[PasskeyRegistrationResponse]
  given Schema[AttestationResponse]           = Schema.derived[AttestationResponse]
  given Schema[PasskeyAuthenticationOptions]  = Schema.derived[PasskeyAuthenticationOptions]
  given Schema[AllowCredential]               = Schema.derived[AllowCredential]
  given Schema[PasskeyAuthenticationResponse] = Schema.derived[PasskeyAuthenticationResponse]
  given Schema[AssertionResponse]             = Schema.derived[AssertionResponse]
}
