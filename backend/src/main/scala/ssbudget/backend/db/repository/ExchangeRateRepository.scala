package ssbudget.backend.db.repository

import cats.effect.IO
import doobie.*
import doobie.implicits.*
import ssbudget.backend.db.DoobieMeta.given
import ssbudget.shared.model.*

trait ExchangeRateRepository {

  /** Stores the rates, in one transaction. A rate of a pair and day that is already there is replaced. */
  def upsertAll(rates: List[ExchangeRate]): IO[Unit]

  /** The newest rate from each currency to `to`. */
  def findLatestTo(to: Currency): IO[List[ExchangeRate]]

  /** Every rate to `to`, oldest day first: the history that a figure of a past day is converted with. */
  def findAllTo(to: Currency): IO[List[ExchangeRate]]
}

class ExchangeRateRepositoryImpl(xa: Transactor[IO]) extends ExchangeRateRepository {

  private val columns = fr"from_currency, to_currency, rate, on_date, fetched_at"

  override def upsertAll(rates: List[ExchangeRate]): IO[Unit] =
    Update[(Currency, Currency, java.time.LocalDate, Long, java.time.Instant)](
      """INSERT INTO exchange_rates (from_currency, to_currency, on_date, rate, fetched_at) VALUES (?, ?, ?, ?, ?)
         ON CONFLICT (from_currency, to_currency, on_date) DO UPDATE SET rate = excluded.rate, fetched_at = excluded.fetched_at""",
    ).updateMany(rates.map(r => (r.fromCurrency, r.toCurrency, r.onDate, r.rate, r.fetchedAt))).transact(xa).void

  override def findLatestTo(to: Currency): IO[List[ExchangeRate]] =
    (fr"SELECT" ++ columns ++ fr"""FROM exchange_rates r
      WHERE r.to_currency = $to
        AND r.on_date = (SELECT MAX(n.on_date) FROM exchange_rates n WHERE n.from_currency = r.from_currency AND n.to_currency = r.to_currency)
      ORDER BY r.from_currency""").query[ExchangeRate].to[List].transact(xa)

  override def findAllTo(to: Currency): IO[List[ExchangeRate]] =
    (fr"SELECT" ++ columns ++ fr"FROM exchange_rates WHERE to_currency = $to ORDER BY on_date, from_currency")
      .query[ExchangeRate]
      .to[List]
      .transact(xa)
}
