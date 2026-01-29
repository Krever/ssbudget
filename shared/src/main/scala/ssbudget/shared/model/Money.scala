package ssbudget.shared.model

import io.circe.{Codec, Decoder, Encoder}

final case class Currency(code: String) extends AnyVal

object Currency {
  // Common currency constants for convenience
  val PLN: Currency = Currency("PLN")
  val EUR: Currency = Currency("EUR")
  val USD: Currency = Currency("USD")
  val GBP: Currency = Currency("GBP")

  // Encode as plain string, not object
  given Encoder[Currency] = Encoder.encodeString.contramap(_.code)
  given Decoder[Currency] = Decoder.decodeString.map(Currency.apply)

  // List of known ISO 4217 currency codes with names (for UI autocomplete)
  val knownCurrencies: List[(String, String)] = List(
    ("AUD", "Australian Dollar"),
    ("BGN", "Bulgarian Lev"),
    ("BRL", "Brazilian Real"),
    ("CAD", "Canadian Dollar"),
    ("CHF", "Swiss Franc"),
    ("CNY", "Chinese Yuan"),
    ("CZK", "Czech Koruna"),
    ("DKK", "Danish Krone"),
    ("EUR", "Euro"),
    ("GBP", "British Pound"),
    ("HKD", "Hong Kong Dollar"),
    ("HRK", "Croatian Kuna"),
    ("HUF", "Hungarian Forint"),
    ("IDR", "Indonesian Rupiah"),
    ("ILS", "Israeli Shekel"),
    ("INR", "Indian Rupee"),
    ("ISK", "Icelandic Krona"),
    ("JPY", "Japanese Yen"),
    ("KRW", "South Korean Won"),
    ("MXN", "Mexican Peso"),
    ("MYR", "Malaysian Ringgit"),
    ("NOK", "Norwegian Krone"),
    ("NZD", "New Zealand Dollar"),
    ("PHP", "Philippine Peso"),
    ("PLN", "Polish Zloty"),
    ("RON", "Romanian Leu"),
    ("SEK", "Swedish Krona"),
    ("SGD", "Singapore Dollar"),
    ("THB", "Thai Baht"),
    ("TRY", "Turkish Lira"),
    ("USD", "US Dollar"),
    ("ZAR", "South African Rand"),
  )

  def isKnown(code: String): Boolean = knownCurrencies.exists(_._1 == code)

  def nameFor(code: String): Option[String] = knownCurrencies.find(_._1 == code).map(_._2)
}

final case class Money(amountCents: Long, currency: Currency) derives Codec.AsObject {
  def toDouble: Double = amountCents / 100.0

  def formatted: String = s"${amountCents / 100.0} ${currency.code}"

  def +(other: Money): Money = {
    require(currency == other.currency, s"Cannot add $currency and ${other.currency}")
    Money(amountCents + other.amountCents, currency)
  }

  def -(other: Money): Money = {
    require(currency == other.currency, s"Cannot subtract $currency and ${other.currency}")
    Money(amountCents - other.amountCents, currency)
  }

  def *(factor: Double): Money = {
    Money((amountCents * factor).toLong, currency)
  }

  def /(divisor: Double): Money = {
    Money((amountCents / divisor).toLong, currency)
  }
}

object Money {
  def fromDouble(amount: Double, currency: Currency): Money = {
    Money((amount * 100).toLong, currency)
  }

  def fromCents(cents: Long, currency: Currency): Money = Money(cents, currency)

  def zero(currency: Currency): Money = Money(0, currency)

}
