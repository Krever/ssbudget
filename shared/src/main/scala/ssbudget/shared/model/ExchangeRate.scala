package ssbudget.shared.model

import io.circe.Codec
import java.time.Instant

final case class ExchangeRate(
    fromCurrency: Currency,
    toCurrency: Currency,
    rate: Long, // rate * 10000 for precision (e.g., 4.5 PLN/EUR = 45000)
    fetchedAt: Instant,
) derives Codec.AsObject {
  def rateAsDouble: Double = rate / 10000.0

  def convert(money: Money): Money = {
    require(money.currency == fromCurrency, s"Expected $fromCurrency but got ${money.currency}")
    Money((money.amountCents * rate / 10000).toLong, toCurrency)
  }
}

object ExchangeRate {
  def fromDouble(
      from: Currency,
      to: Currency,
      rate: Double,
      fetchedAt: Instant,
  ): ExchangeRate = {
    ExchangeRate(from, to, (rate * 10000).toLong, fetchedAt)
  }
}
