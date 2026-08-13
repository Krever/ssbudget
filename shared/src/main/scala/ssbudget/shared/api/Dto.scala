package ssbudget.shared.api

import io.circe.Codec
import ssbudget.shared.model.*

// Request DTOs
final case class CreateAccount(
    name: String,
    currency: Currency,
    role: AccountRole,
) derives Codec.AsObject

final case class UpdateAccount(name: String, currency: Currency) derives Codec.AsObject

/** Set an account's balance directly. Rejected server-side unless the account's balanceSource is Manual. */
final case class UpdateAccountBalance(newBalanceCents: Long) derives Codec.AsObject

/** A savings account's balance as of the current period's start (else its earliest recorded balance), in the account's own currency. The client
  * derives the period delta as current balance − baseline, so the Δ stays fresh as balances are edited or synced, with no re-fetch to coordinate.
  * Savings only: a spending account's balance moves with every purchase, so its period delta carries no signal.
  */
final case class AccountPeriodBaseline(accountId: AccountId, baseline: Money) derives Codec.AsObject

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

/** Record a payment against a planned item in the current period. `amountCents` is ADDED to whatever was already paid this period, so instalments
  * accumulate. `settle = true` closes the item (nothing more expected, whatever the estimate said); `false` records a part-payment and leaves the
  * remainder outstanding.
  */
final case class PayBudgetItem(amountCents: Long, settle: Boolean) derives Codec.AsObject

// Response DTOs
final case class IdResponse(id: String) derives Codec.AsObject

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
