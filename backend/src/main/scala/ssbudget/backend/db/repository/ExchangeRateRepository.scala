package ssbudget.backend.db.repository

import cats.effect.IO
import doobie.*
import doobie.implicits.*
import ssbudget.backend.db.DoobieMeta.given
import ssbudget.shared.model.*

trait ExchangeRateRepository {
  def create(rate: ExchangeRate): IO[Unit]
  def findLatest(from: Currency, to: Currency): IO[Option[ExchangeRate]]
  def findAll: IO[List[ExchangeRate]]

  /** The newest rate from each currency to `to`. */
  def findLatestTo(to: Currency): IO[List[ExchangeRate]]
}

class ExchangeRateRepositoryImpl(xa: Transactor[IO]) extends ExchangeRateRepository {

  override def create(rate: ExchangeRate): IO[Unit] = {
    sql"""
      INSERT INTO exchange_rates (from_currency, to_currency, rate, fetched_at)
      VALUES (${rate.fromCurrency}, ${rate.toCurrency}, ${rate.rate}, ${rate.fetchedAt})
    """.update.run.transact(xa).void
  }

  override def findLatest(from: Currency, to: Currency): IO[Option[ExchangeRate]] = {
    sql"""
      SELECT from_currency, to_currency, rate, fetched_at
      FROM exchange_rates
      WHERE from_currency = $from AND to_currency = $to
      ORDER BY fetched_at DESC LIMIT 1
    """.query[ExchangeRate].option.transact(xa)
  }

  override def findAll: IO[List[ExchangeRate]] = {
    sql"""
      SELECT from_currency, to_currency, rate, fetched_at
      FROM exchange_rates ORDER BY fetched_at DESC
    """.query[ExchangeRate].to[List].transact(xa)
  }

  override def findLatestTo(to: Currency): IO[List[ExchangeRate]] =
    sql"""
      SELECT r.from_currency, r.to_currency, r.rate, r.fetched_at
      FROM exchange_rates r
      WHERE r.to_currency = $to
        AND r.fetched_at = (SELECT MAX(n.fetched_at) FROM exchange_rates n WHERE n.from_currency = r.from_currency AND n.to_currency = r.to_currency)
      GROUP BY r.from_currency
    """.query[ExchangeRate].to[List].transact(xa)
}
