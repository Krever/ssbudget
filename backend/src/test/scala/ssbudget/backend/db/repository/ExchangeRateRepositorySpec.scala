package ssbudget.backend.db.repository

import ssbudget.shared.model.*

import java.time.{Instant, LocalDate}

class ExchangeRateRepositorySpec extends RepositorySpec {

  private def eur(rate: Long, day: String, fetched: String = "2026-11-27T10:00:00Z") =
    ExchangeRate(Currency.EUR, Currency.PLN, rate, LocalDate.parse(day), Instant.parse(fetched))

  "a stored rate is found again" in {
    val repo = new ExchangeRateRepositoryImpl(xa)
    val rate = eur(45000L, "2024-01-15")
    for {
      _     <- repo.upsertAll(List(rate))
      found <- repo.findLatestTo(Currency.PLN)
    } yield found shouldBe List(rate)
  }

  "the latest rate is the one of the newest day, not of the newest fetch" in {
    val repo = new ExchangeRateRepositoryImpl(xa)
    for {
      _      <- repo.upsertAll(List(eur(45000L, "2024-01-15", "2024-01-15T17:00:00Z")))
      // A fill of the history fetches an older day later.
      _      <- repo.upsertAll(List(eur(43000L, "2024-01-10", "2024-02-01T10:00:00Z")))
      latest <- repo.findLatestTo(Currency.PLN)
    } yield latest.map(_.rate) shouldBe List(45000L)
  }

  "a second rate of the same pair and day replaces the first" in {
    val repo = new ExchangeRateRepositoryImpl(xa)
    for {
      _   <- repo.upsertAll(List(eur(45000L, "2024-01-15", "2024-01-15T10:00:00Z")))
      _   <- repo.upsertAll(List(eur(45100L, "2024-01-15", "2024-01-15T17:00:00Z")))
      all <- repo.findAllTo(Currency.PLN)
    } yield all shouldBe List(eur(45100L, "2024-01-15", "2024-01-15T17:00:00Z"))
  }

  "there is no rate to a currency that no rate was stored for" in {
    val repo = new ExchangeRateRepositoryImpl(xa)
    for {
      _     <- repo.upsertAll(List(eur(45000L, "2024-01-15")))
      found <- repo.findLatestTo(Currency.EUR)
    } yield found shouldBe Nil
  }

  "the history to a currency comes oldest day first, and holds only the rates to it" in {
    val repo = new ExchangeRateRepositoryImpl(xa)
    val usd  = ExchangeRate(Currency.USD, Currency.PLN, 39000L, LocalDate.parse("2024-01-12"), Instant.parse("2026-11-27T10:00:00Z"))
    val back = ExchangeRate(Currency.PLN, Currency.EUR, 2300L, LocalDate.parse("2024-01-12"), Instant.parse("2026-11-27T10:00:00Z"))
    for {
      _       <- repo.upsertAll(List(eur(45000L, "2024-01-15"), usd, back, eur(43000L, "2024-01-10")))
      history <- repo.findAllTo(Currency.PLN)
    } yield history shouldBe List(eur(43000L, "2024-01-10"), usd, eur(45000L, "2024-01-15"))
  }
}
