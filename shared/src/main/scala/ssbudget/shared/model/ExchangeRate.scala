package ssbudget.shared.model

import io.circe.Codec
import java.time.{Instant, LocalDate}

/** The rate of one currency to another on one day.
  *
  * @param onDate
  *   the day the rate is for: the date of the reference rate that the source published (a business day). An amount of a later day with no rate of its
  *   own (a weekend, a holiday) takes the rate of the last day before it.
  * @param fetchedAt
  *   when the app got the rate from the source
  */
final case class ExchangeRate(
    fromCurrency: Currency,
    toCurrency: Currency,
    rate: Long, // rate * 10000 for precision (e.g., 4.5 PLN/EUR = 45000)
    onDate: LocalDate,
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
      onDate: LocalDate,
      fetchedAt: Instant,
  ): ExchangeRate = {
    ExchangeRate(from, to, Math.round(rate * 10000), onDate, fetchedAt)
  }
}
