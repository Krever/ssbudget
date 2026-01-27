package ssbudget.backend.db.repository

import cats.effect.IO
import ssbudget.shared.model.*

import java.time.Instant

class PeriodRepositorySpec extends RepositorySpec {

  "create and findById returns the period" in {
    val repo   = new PeriodRepositoryImpl(xa)
    val period = Period(PeriodId("per-1"), Instant.parse("2024-01-25T00:00:00Z"), None)

    for {
      _     <- repo.create(period)
      found <- repo.findById(PeriodId("per-1"))
    } yield found shouldBe Some(period)
  }

  "findById returns None for non-existent period" in {
    val repo = new PeriodRepositoryImpl(xa)

    for {
      found <- repo.findById(PeriodId("non-existent"))
    } yield found shouldBe None
  }

  "findCurrent returns open period (no endDate)" in {
    val repo    = new PeriodRepositoryImpl(xa)
    val closed  = Period(PeriodId("per-1"), Instant.parse("2024-01-25T00:00:00Z"), Some(Instant.parse("2024-02-24T00:00:00Z")))
    val current = Period(PeriodId("per-2"), Instant.parse("2024-02-25T00:00:00Z"), None)

    for {
      _     <- repo.create(closed)
      _     <- repo.create(current)
      found <- repo.findCurrent
    } yield found shouldBe Some(current)
  }

  "findCurrent returns None when all periods are closed" in {
    val repo   = new PeriodRepositoryImpl(xa)
    val closed = Period(PeriodId("per-1"), Instant.parse("2024-01-25T00:00:00Z"), Some(Instant.parse("2024-02-24T00:00:00Z")))

    for {
      _     <- repo.create(closed)
      found <- repo.findCurrent
    } yield found shouldBe None
  }

  "findAll returns periods ordered by startDate descending" in {
    val repo = new PeriodRepositoryImpl(xa)
    val p1   = Period(PeriodId("per-1"), Instant.parse("2024-01-25T00:00:00Z"), Some(Instant.parse("2024-02-24T00:00:00Z")))
    val p2   = Period(PeriodId("per-2"), Instant.parse("2024-02-25T00:00:00Z"), Some(Instant.parse("2024-03-24T00:00:00Z")))
    val p3   = Period(PeriodId("per-3"), Instant.parse("2024-03-25T00:00:00Z"), None)

    for {
      _   <- repo.create(p1)
      _   <- repo.create(p2)
      _   <- repo.create(p3)
      all <- repo.findAll
    } yield all shouldBe List(p3, p2, p1)
  }

  "close sets endDate on period" in {
    val repo    = new PeriodRepositoryImpl(xa)
    val period  = Period(PeriodId("per-1"), Instant.parse("2024-01-25T00:00:00Z"), None)
    val endedAt = Instant.parse("2024-02-24T00:00:00Z")

    for {
      _     <- repo.create(period)
      _     <- repo.close(PeriodId("per-1"), endedAt)
      found <- repo.findById(PeriodId("per-1"))
    } yield found shouldBe Some(period.copy(endDate = Some(endedAt)))
  }

  "delete removes period" in {
    val repo   = new PeriodRepositoryImpl(xa)
    val period = Period(PeriodId("per-1"), Instant.parse("2024-01-25T00:00:00Z"), None)

    for {
      _     <- repo.create(period)
      _     <- repo.delete(PeriodId("per-1"))
      found <- repo.findById(PeriodId("per-1"))
    } yield found shouldBe None
  }
}
