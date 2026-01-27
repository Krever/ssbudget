package ssbudget.shared.json

import io.circe.{Codec, Decoder, Encoder}

/** Typeclass for String-based ID types (AnyVal wrappers).
  *
  * Usage:
  * {{{
  * final case class AccountId(value: String) extends AnyVal
  * object AccountId extends StringId[AccountId]
  * }}}
  */
trait StringId[T <: Product] {
  def apply(value: String): T
  def value(t: T): String = t.productElement(0).asInstanceOf[String]

  given Codec[T] = Codec.from(
    Decoder.decodeString.map(apply),
    Encoder.encodeString.contramap(value),
  )
}
