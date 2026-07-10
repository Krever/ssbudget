package ssbudget.shared.model

import io.circe.Codec
import ssbudget.shared.json.EnumCodec

/** Where a transaction's category came from: set by the user, or by the rule engine. Rules never overwrite a Manual category. */
enum CategorySource {
  case Manual, Rule
}

object CategorySource {
  def asString(s: CategorySource): String = s match {
    case Manual => "manual"
    case Rule   => "rule"
  }

  def fromString(s: String): Either[String, CategorySource] = s match {
    case "manual" => Right(Manual)
    case "rule"   => Right(Rule)
    case other    => Left(s"Unknown category source: $other")
  }

  given Codec[CategorySource] = EnumCodec(values, asString, "category source")
}
