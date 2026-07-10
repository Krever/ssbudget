package ssbudget.shared.model

import io.circe.Codec
import ssbudget.shared.json.{EnumCodec, StringId}

import java.time.Instant

final case class BankTransactionId(value: String) extends AnyVal

object BankTransactionId extends StringId[BankTransactionId]

/** Booked = settled on the account; Pending = authorized but not yet settled. */
enum TransactionStatus {
  case Booked, Pending
}

object TransactionStatus {
  def asString(s: TransactionStatus): String = s match {
    case Booked  => "booked"
    case Pending => "pending"
  }

  def fromString(s: String): Either[String, TransactionStatus] = s match {
    case "booked"  => Right(Booked)
    case "pending" => Right(Pending)
    case other     => Left(s"Unknown transaction status: $other")
  }

  given Codec[TransactionStatus] = EnumCodec(values, asString, "transaction status")
}

/** A bank transaction imported from Enable Banking. Aggregators report fields inconsistently, so alongside the best-effort extracted columns we
  * always retain the full payload in [[rawJson]]. Its budget period is derived from [[bookedAt]] at read time (not stored) since periods can shift.
  */
final case class BankTransaction(
    id: BankTransactionId,
    connectionId: BankConnectionId,
    ebAccountUid: String,
    entryReference: Option[String],         // the bank's own transaction id, when present
    dedupKey: String,                       // entryReference if present, else a stable hash of the salient fields
    amountCents: Long,                      // signed: negative = debit/outflow, positive = credit/inflow
    currency: Currency,
    status: TransactionStatus,
    bookedAt: Instant,                      // booking date (falls back to value/transaction date) — drives period assignment + display
    counterpartyName: Option[String],
    counterpartyAccount: Option[String],
    remittance: Option[String],             // remittance_information, joined
    bankTransactionCode: Option[String],
    categoryId: Option[CategoryId],         // None = uncategorized
    rawJson: String,                        // full Enable Banking payload, always retained
    importedAt: Instant,
    internal: Boolean,                      // true when this is a transfer between the user's own accounts (built-in rule)
    categorySource: Option[CategorySource], // who set categoryId (Manual wins over Rule); None when uncategorized
    note: Option[String] = None,            // user's free-text comment; preserved across re-imports
) derives Codec.AsObject {
  def money: Money       = Money(amountCents, currency)
  def isOutflow: Boolean = amountCents < 0
}
