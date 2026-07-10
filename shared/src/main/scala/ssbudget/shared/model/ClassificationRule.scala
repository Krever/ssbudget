package ssbudget.shared.model

import io.circe.syntax.*
import io.circe.{Codec, Decoder, DecodingFailure, Encoder, Json}
import ssbudget.shared.json.{EnumCodec, StringId}

import java.time.Instant

final case class ClassificationRuleId(value: String) extends AnyVal

object ClassificationRuleId extends StringId[ClassificationRuleId]

/** How a text criterion is compared (after normalisation). */
enum TextMatchOp {
  case Contains, Equals
}

object TextMatchOp {
  def asString(o: TextMatchOp): String = o match {
    case Contains => "contains"
    case Equals   => "equals"
  }

  def fromString(s: String): Either[String, TextMatchOp] = s match {
    case "contains" => Right(Contains)
    case "equals"   => Right(Equals)
    case other      => Left(s"Unknown text match op: $other")
  }

  given Codec[TextMatchOp] = EnumCodec(values, asString, "text match op")
}

/** How an amount criterion is compared (on |amount|). */
enum AmountMatchOp {
  case Lt, Eq, Gt
}

object AmountMatchOp {
  def asString(o: AmountMatchOp): String = o match {
    case Lt => "lt"
    case Eq => "eq"
    case Gt => "gt"
  }

  def fromString(s: String): Either[String, AmountMatchOp] = s match {
    case "lt"  => Right(Lt)
    case "eq"  => Right(Eq)
    case "gt"  => Right(Gt)
    case other => Left(s"Unknown amount match op: $other")
  }

  given Codec[AmountMatchOp] = EnumCodec(values, asString, "amount match op")
}

/** A single condition. A rule's criteria are ANDed together (OR = a separate rule). Typed ADT with a manual `kind`-discriminated JSON codec (as with
  * [[BankLinkTarget]]) — the JSON is both the wire format and the stored form.
  */
enum RuleCriterion {
  case CounterpartyName(op: TextMatchOp, value: String)
  case Remittance(op: TextMatchOp, value: String)
  case CounterpartyAccount(iban: String)                   // normalized-equals
  case BankTransactionCode(code: String)                   // equals
  case Account(ebAccountUid: String)                       // equals
  case Direction(outflow: Boolean)                         // sign of amountCents
  case AmountCompare(op: AmountMatchOp, absCents: Long)    // on |amount|
  case AmountBetween(minAbsCents: Long, maxAbsCents: Long) // inclusive, on |amount|
  case CurrencyIs(currency: Currency)
}

object RuleCriterion {
  given Codec[RuleCriterion] = Codec.from(
    Decoder.instance { c =>
      c.get[String]("kind").flatMap {
        case "counterpartyName"    => for { op <- c.get[TextMatchOp]("op"); v <- c.get[String]("value") } yield CounterpartyName(op, v)
        case "remittance"          => for { op <- c.get[TextMatchOp]("op"); v <- c.get[String]("value") } yield Remittance(op, v)
        case "counterpartyAccount" => c.get[String]("iban").map(CounterpartyAccount(_))
        case "bankTransactionCode" => c.get[String]("code").map(BankTransactionCode(_))
        case "account"             => c.get[String]("ebAccountUid").map(Account(_))
        case "direction"           => c.get[Boolean]("outflow").map(Direction(_))
        case "amountEquals"        => c.get[Long]("absCents").map(AmountCompare(AmountMatchOp.Eq, _)) // legacy stored form
        case "amountCompare"       => for { op <- c.get[AmountMatchOp]("op"); a <- c.get[Long]("absCents") } yield AmountCompare(op, a)
        case "amountBetween"       => for { lo <- c.get[Long]("min"); hi <- c.get[Long]("max") } yield AmountBetween(lo, hi)
        case "currencyIs"          => c.get[Currency]("currency").map(CurrencyIs(_))
        case other                 => Left(DecodingFailure(s"Unknown criterion kind: $other", c.history))
      }
    },
    Encoder.instance {
      case CounterpartyName(op, v)   => Json.obj("kind" := "counterpartyName", "op" := op, "value" := v)
      case Remittance(op, v)         => Json.obj("kind" := "remittance", "op" := op, "value" := v)
      case CounterpartyAccount(iban) => Json.obj("kind" := "counterpartyAccount", "iban" := iban)
      case BankTransactionCode(code) => Json.obj("kind" := "bankTransactionCode", "code" := code)
      case Account(uid)              => Json.obj("kind" := "account", "ebAccountUid" := uid)
      case Direction(outflow)        => Json.obj("kind" := "direction", "outflow" := outflow)
      case AmountCompare(op, a)      => Json.obj("kind" := "amountCompare", "op" := op, "absCents" := a)
      case AmountBetween(lo, hi)     => Json.obj("kind" := "amountBetween", "min" := lo, "max" := hi)
      case CurrencyIs(cur)           => Json.obj("kind" := "currencyIs", "currency" := cur)
    },
  )
}

/** A user-defined categorization rule. Rules are evaluated in ascending [[priority]]; the first whose [[criteria]] all match wins. */
final case class ClassificationRule(
    id: ClassificationRuleId,
    name: String,
    categoryId: CategoryId,
    priority: Int,
    criteria: List[RuleCriterion],
    createdAt: Instant,
) derives Codec.AsObject
