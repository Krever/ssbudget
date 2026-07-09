package ssbudget.shared.model

import io.circe.{Codec, Decoder, Encoder}
import io.circe.syntax.*
import ssbudget.shared.json.{EnumCodec, StringId}

import java.time.Instant

final case class BankConnectionId(value: String) extends AnyVal

object BankConnectionId extends StringId[BankConnectionId]

final case class BankAccountLinkId(value: String) extends AnyVal

object BankAccountLinkId extends StringId[BankAccountLinkId]

/** Lifecycle of a bank connection (Enable Banking session/consent). */
enum ConnectionStatus {
  case Pending // authorization started, waiting for the user to finish SCA at the bank
  case Active  // session established, consent valid
  case Expired // consent expired, needs re-authorization
  case Revoked // consent revoked (by us or the bank)
}

object ConnectionStatus {
  def asString(s: ConnectionStatus): String = s match {
    case Pending => "pending"
    case Active  => "active"
    case Expired => "expired"
    case Revoked => "revoked"
  }

  def fromString(s: String): Either[String, ConnectionStatus] = s match {
    case "pending" => Right(Pending)
    case "active"  => Right(Active)
    case "expired" => Right(Expired)
    case "revoked" => Right(Revoked)
    case other     => Left(s"Unknown connection status: $other")
  }

  given Codec[ConnectionStatus] = EnumCodec(values, asString, "connection status")
}

/** A linked bank (Enable Banking authorization/session). The Enable Banking signing key never lives here — only the session handle and consent
  * metadata.
  */
final case class BankConnection(
    id: BankConnectionId,
    aspspName: String,
    aspspCountry: String,
    sessionId: Option[String],   // Enable Banking session_id, null until authorized
    status: ConnectionStatus,
    validUntil: Option[Instant], // access.valid_until returned by Enable Banking
    authState: Option[String],   // CSRF nonce for the redirect, cleared once authorized
    createdAt: Instant,
) derives Codec.AsObject

/** What an Enable Banking account's balance feeds into. A bank account either mirrors a single app [[Account]] directly, or contributes as a member
  * of a shared-limit [[CardGroup]] — mutually exclusive, so this is one sum type rather than several nullable ids.
  */
enum BankLinkTarget {
  case Unlinked
  case Account(id: AccountId)           // direct mirror onto an app account (Spending or Savings)
  case CardGroupMember(id: CardGroupId) // this card is a member of a shared-limit group
}

object BankLinkTarget {

  /** Discriminator + id, mirroring how the link is stored (`link_target_kind`, `link_target_id`). */
  def kind(t: BankLinkTarget): String = t match {
    case Unlinked           => "none"
    case Account(_)         => "account"
    case CardGroupMember(_) => "card_group"
  }

  def idValue(t: BankLinkTarget): Option[String] = t match {
    case Unlinked            => None
    case Account(id)         => Some(id.value)
    case CardGroupMember(id) => Some(id.value)
  }

  def fromParts(kind: String, id: Option[String]): BankLinkTarget = (kind, id) match {
    case ("account", Some(v))    => Account(AccountId(v))
    case ("card_group", Some(v)) => CardGroupMember(CardGroupId(v))
    case _                       => Unlinked
  }

  given Codec[BankLinkTarget] = Codec.from(
    Decoder.instance { c =>
      for {
        k  <- c.get[String]("kind")
        id <- c.get[Option[String]]("id")
      } yield fromParts(k, id)
    },
    Encoder.instance { t =>
      io.circe.Json.obj("kind" := kind(t), "id" := idValue(t))
    },
  )
}

/** Maps an Enable Banking account (uid) to its [[target]] (nullable until the user links it), caches the bank-reported metadata + last-seen balance
  * (the bank is only queried on sync, not on page load), and never holds the app-account's own balance — that lives on the [[Account]].
  */
final case class BankAccountLink(
    id: BankAccountLinkId,
    connectionId: BankConnectionId,
    ebAccountUid: String,
    target: BankLinkTarget,
    iban: Option[String],
    name: Option[String],
    currency: Option[Currency],
    product: Option[String],
    // Last balance the bank reported for this bank account, cached from the most recent sync.
    lastBalanceCents: Option[Long],
    lastBalanceCurrency: Option[Currency],
    lastSyncedAt: Option[Instant],
) derives Codec.AsObject
