package ssbudget.shared.model

import io.circe.Codec
import ssbudget.shared.json.EnumCodec

enum Currency {
  case PLN, EUR
}

object Currency {
  given Codec[Currency] = EnumCodec(Currency.values, _.toString, "currency")
}

final case class Money(amountCents: Long, currency: Currency) derives Codec.AsObject {
  def toDouble: Double = amountCents / 100.0

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

  def zero(currency: Currency): Money = Money(0, currency)
}
