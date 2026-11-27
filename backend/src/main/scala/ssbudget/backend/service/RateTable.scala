package ssbudget.backend.service

import cats.effect.IO
import ssbudget.backend.db.Repositories
import ssbudget.shared.model.*

import java.time.LocalDate

/** Conversion into the primary currency at the rate of a given day. Every figure of a past day (a balance at a period boundary, a movement of the
  * ledger, a day of the daily record) is converted with it.
  *
  * The rate of a day is the newest rate on or before that day: the source has no rate for a weekend or a holiday. A day before the first rate takes
  * the first rate. A currency with no rate at all counts 1 to 1; `DataHealth` tells the user of each such currency.
  */
final class RateTable private (val primary: Currency, toPrimary: Map[Currency, RateTable.Series]) {

  def convert(cents: Long, currency: Currency, day: LocalDate): Long =
    if currency == primary then cents
    else toPrimary.get(currency).fold(cents)(_.on(day).convert(Money(cents, currency)).amountCents)

  /** The days that have a rate, oldest first, for each currency. */
  def days: Map[Currency, Seq[LocalDate]] = toPrimary.view.mapValues(_.days).toMap
}

object RateTable {

  /** The rates of one currency, sorted by day, with the days as numbers for a binary search. */
  final private class Series(rates: Vector[ExchangeRate]) {
    private val epochDays = rates.map(_.onDate.toEpochDay).toArray

    def days: Seq[LocalDate] = rates.map(_.onDate)

    def on(day: LocalDate): ExchangeRate = {
      val found = java.util.Arrays.binarySearch(epochDays, day.toEpochDay)
      // Not found: `-(insertion point) - 1`. The rate before the insertion point is the newest one before the day.
      val index = if found >= 0 then found else math.max(-found - 2, 0)
      rates(index)
    }
  }

  def apply(primary: Currency, rates: List[ExchangeRate]): RateTable =
    new RateTable(
      primary,
      rates
        .filter(r => r.toCurrency == primary && r.fromCurrency != primary)
        .groupBy(_.fromCurrency)
        .view
        .mapValues(rs => new Series(rs.sortBy(_.onDate.toEpochDay).toVector))
        .toMap,
    )

  /** The primary currency and all rates to it. */
  def load(repos: Repositories): IO[RateTable] =
    for {
      primaryOpt <- repos.currencySettings.findPrimary
      primary     = primaryOpt.map(_.code).getOrElse(Currency.PLN)
      rates      <- repos.exchangeRates.findAllTo(primary)
    } yield RateTable(primary, rates)
}
