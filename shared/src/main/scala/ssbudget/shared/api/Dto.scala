package ssbudget.shared.api

import io.circe.Codec
import ssbudget.shared.model.*

// Request DTOs
final case class CreateAccount(
    name: String,
    currency: Currency,
    role: AccountRole,
) derives Codec.AsObject

/** `role` changes the role of the account. None keeps the role. An account that mirrors a card group stays a spending account. */
final case class UpdateAccount(name: String, currency: Currency, role: Option[AccountRole] = None) derives Codec.AsObject

/** Set an account's balance directly. Rejected server-side unless the account's balanceSource is Manual. */
final case class UpdateAccountBalance(newBalanceCents: Long) derives Codec.AsObject

/** The balance of a savings or investment account as of the current period's start (else its earliest recorded balance), in the account's own
  * currency. The client derives the period delta as current balance − baseline, so the Δ stays fresh as balances are edited or synced, with no
  * re-fetch to coordinate. Not for spending accounts: a spending account's balance moves with every purchase, so its period delta carries no signal.
  *
  * `movedCents` is for an investment account: the net amount moved INTO it in the current period (negative = withdrawn), in the PRIMARY currency,
  * read from the bank transactions of its transfer categories (see `Category.transferAccount`). The rest of the balance change is market result.
  *
  * `currencyEffectCents` is what the exchange rate did to the baseline since the period started, in the PRIMARY currency: the baseline at the rate of
  * now, less the same at the rate of the period's start (`PeriodService.HeldChange`). Zero for an account in the primary currency. The market result
  * holds it; the change in the account's own currency at the rate of now does not.
  */
final case class AccountPeriodBaseline(accountId: AccountId, baseline: Money, currencyEffectCents: Long, movedCents: Long = 0) derives Codec.AsObject

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

/** A fault in the data that makes a figure wrong (a currency with no exchange rate). `code` names the kind; `message` is the text for the user. */
final case class DataWarning(code: String, message: String) derives Codec.AsObject
