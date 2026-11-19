package ssbudget.shared.model

import io.circe.Codec
import ssbudget.shared.json.{EnumCodec, StringId}

import java.time.Instant

final case class AccountId(value: String) extends AnyVal

object AccountId extends StringId[AccountId]

/** What an account is used for in the budget. */
enum AccountRole {
  case Spending // a spendable account; its balance counts toward the free-money calculation
  case Savings  // a savings bucket; excluded from spendable balance
  // Money whose value moves with a market (a crypto or stock portfolio). It is not spendable, and it is not savings: a change of its value is not
  // money that was saved or withdrawn, so it is reported apart from the savings figures.
  case Investment
  // Money that is in an account of its own and is not for this period: pay that came early and waits for the next period to open. It is not
  // spendable (so it is not in free money), and it is not savings: to put money on hold, or to take it from there, is not saving.
  case Holding
}

object AccountRole {
  def asString(r: AccountRole): String = r match {
    case Spending   => "spending"
    case Savings    => "savings"
    case Investment => "investment"
    case Holding    => "holding"
  }

  def fromString(s: String): Either[String, AccountRole] = s match {
    case "spending"   => Right(Spending)
    case "savings"    => Right(Savings)
    case "investment" => Right(Investment)
    case "holding"    => Right(Holding)
    case other        => Left(s"Unknown account role: $other")
  }

  /** The roles whose balance change of a period is a figure of the app: what was saved, and what an investment did. */
  val held: Set[AccountRole] = Set(Savings, Investment)

  given Codec[AccountRole] = EnumCodec(values, asString, "account role")
}

/** Where an account's current balance comes from — the balance provenance. */
enum BalanceSource {
  case Manual    // the user edits the balance directly
  case Bank      // driven by a bank connection sync
  case CardGroup // computed remaining limit of a shared-limit credit-card group
}

object BalanceSource {
  def asString(s: BalanceSource): String = s match {
    case Manual    => "manual"
    case Bank      => "bank"
    case CardGroup => "card_group"
  }

  def fromString(s: String): Either[String, BalanceSource] = s match {
    case "manual"     => Right(Manual)
    case "bank"       => Right(Bank)
    case "card_group" => Right(CardGroup)
    case other        => Left(s"Unknown balance source: $other")
  }

  given Codec[BalanceSource] = EnumCodec(values, asString, "balance source")
}

/** A money container: a spending account, a savings bucket or an investment. The current balance and its provenance live here uniformly;
  * [[BalanceSnapshot]] rows are append-only history. Intended saving is one amount for each period ([[Period.plannedSavingCents]]), not a target on
  * the bucket.
  */
final case class Account(
    id: AccountId,
    name: String,
    currency: Currency,
    role: AccountRole,
    balanceCents: Long,               // current balance
    balanceSource: BalanceSource,     // Manual = user-editable; Bank/CardGroup = driven externally, read-only
    balanceUpdatedAt: Option[Instant], // when the balance was last set
) derives Codec.AsObject {
  def balance: Money    = Money(balanceCents, currency)
  def isManual: Boolean = balanceSource == BalanceSource.Manual
}
