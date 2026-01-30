package ssbudget.backend.db.repository

import cats.effect.IO
import ssbudget.shared.model.*

import java.time.Instant

class ExchangeRateRepositorySpec extends RepositorySpec {

  "create and findLatest returns the exchange rate" in {
    val repo = new ExchangeRateRepositoryImpl(xa)
    val rate = ExchangeRate(
      Currency.EUR,
      Currency.PLN,
      45000L, // 4.5 PLN/EUR
      Instant.parse("2024-01-15T10:00:00Z"),
    )

    for {
      _     <- repo.create(rate)
      found <- repo.findLatest(Currency.EUR, Currency.PLN)
    } yield found shouldBe Some(rate)
  }

  "findLatest returns most recent rate for currency pair" in {
    val repo  = new ExchangeRateRepositoryImpl(xa)
    val rate1 = ExchangeRate(Currency.EUR, Currency.PLN, 43000L, Instant.parse("2024-01-10T10:00:00Z"))
    val rate2 = ExchangeRate(Currency.EUR, Currency.PLN, 45000L, Instant.parse("2024-01-15T10:00:00Z"))
    val rate3 = ExchangeRate(Currency.EUR, Currency.PLN, 44000L, Instant.parse("2024-01-12T10:00:00Z"))

    for {
      _      <- repo.create(rate1)
      _      <- repo.create(rate2)
      _      <- repo.create(rate3)
      latest <- repo.findLatest(Currency.EUR, Currency.PLN)
    } yield latest shouldBe Some(rate2)
  }

  "findLatest returns None when no rates exist for pair" in {
    val repo = new ExchangeRateRepositoryImpl(xa)
    val rate = ExchangeRate(Currency.EUR, Currency.PLN, 45000L, Instant.parse("2024-01-15T10:00:00Z"))

    for {
      _     <- repo.create(rate)
      found <- repo.findLatest(Currency.PLN, Currency.EUR)
    } yield found shouldBe None
  }

  "findAll returns all rates ordered by fetched_at desc" in {
    val repo  = new ExchangeRateRepositoryImpl(xa)
    val rate1 = ExchangeRate(Currency.EUR, Currency.PLN, 43000L, Instant.parse("2024-01-10T10:00:00Z"))
    val rate2 = ExchangeRate(Currency.EUR, Currency.PLN, 45000L, Instant.parse("2024-01-15T10:00:00Z"))

    for {
      _   <- repo.create(rate1)
      _   <- repo.create(rate2)
      all <- repo.findAll
    } yield all shouldBe List(rate2, rate1)
  }
}
