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
