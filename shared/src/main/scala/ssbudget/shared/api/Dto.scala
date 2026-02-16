package ssbudget.shared.api

import io.circe.Codec
import ssbudget.shared.model.*

// Request DTOs
final case class CreateAccount(name: String, currency: Currency) derives Codec.AsObject

final case class CreateBalanceSnapshot(accountId: AccountId, amountCents: Long) derives Codec.AsObject

final case class CreateBudgetItem(
    name: String,
    itemType: BudgetItemType,
    estimateCents: Long,
    currency: Currency,
) derives Codec.AsObject

final case class UpdateBudgetItem(
    name: String,
    itemType: BudgetItemType,
    estimateCents: Long,
    currency: Currency,
) derives Codec.AsObject

final case class PayBudgetItem(amountCents: Long) derives Codec.AsObject

final case class CreateSavingsAccount(name: String, currency: Currency, plannedMonthly: Option[Long]) derives Codec.AsObject

final case class UpdateSavingsAccount(name: String, currency: Currency, plannedMonthly: Option[Long]) derives Codec.AsObject

final case class UpdateSavingsAccountBalance(newBalance: Long) derives Codec.AsObject

final case class CreateSavingsTransaction(accountId: SavingsAccountId, amount: Long, note: Option[String]) derives Codec.AsObject

final case class CreateOneTimeExpense(name: String, amountCents: Long, currency: Currency, date: Option[java.time.Instant]) derives Codec.AsObject

final case class UpdateOneTimeExpense(name: String, amountCents: Long, currency: Currency, date: java.time.Instant) derives Codec.AsObject

// Response DTOs
final case class IdResponse(id: String) derives Codec.AsObject

final case class AccountResponse(account: Account, balance: BalanceSnapshot) derives Codec.AsObject

final case class SavingsTransactionResponse(
    transaction: SavingsTransaction,
    updatedAccount: SavingsAccount,
) derives Codec.AsObject

// Currency settings DTOs
final case class EnableCurrencyRequest(code: String) derives Codec.AsObject

final case class SetPrimaryCurrencyRequest(code: String) derives Codec.AsObject

final case class KnownCurrency(code: String, name: String) derives Codec.AsObject

final case class CurrencySettingsResponse(
    currencies: List[CurrencySetting],
    availableCurrencies: List[KnownCurrency],
) derives Codec.AsObject

final case class ExchangeRatesResponse(
    rates: Map[String, Double],
    baseCurrency: String,
    fetchedAt: java.time.Instant,
) derives Codec.AsObject
