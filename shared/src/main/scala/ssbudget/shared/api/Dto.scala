package ssbudget.shared.api

import io.circe.Codec
import ssbudget.shared.model.*

// Request DTOs
final case class CreateAccount(
    name: String,
    currency: Currency,
    role: AccountRole,
    savingsTarget: Option[Long], // only meaningful for Savings accounts
) derives Codec.AsObject

final case class UpdateAccount(name: String, currency: Currency, savingsTarget: Option[Long]) derives Codec.AsObject

/** Set an account's balance directly. Rejected server-side unless the account's balanceSource is Manual. */
final case class UpdateAccountBalance(newBalanceCents: Long) derives Codec.AsObject

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

final case class CreateSavingsTransaction(accountId: AccountId, amount: Long, note: Option[String]) derives Codec.AsObject

final case class CreateOneTimeExpense(name: String, amountCents: Long, currency: Currency, date: Option[java.time.Instant]) derives Codec.AsObject

final case class UpdateOneTimeExpense(name: String, amountCents: Long, currency: Currency, date: java.time.Instant) derives Codec.AsObject

// Response DTOs
final case class IdResponse(id: String) derives Codec.AsObject

final case class SavingsTransactionResponse(
    transaction: SavingsTransaction,
    updatedAccount: Account,
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
