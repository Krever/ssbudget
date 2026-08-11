package ssbudget.backend.db.repository

import ssbudget.shared.model.*

import java.time.Instant

class CategoryBudgetOverrideRepositorySpec extends RepositorySpec {

  private val period1 = PeriodId("p-1")
  private val period2 = PeriodId("p-2")
  private val catA    = CategoryId("c-a")
  private val catB    = CategoryId("c-b")
  private val at      = Instant.parse("2024-01-01T00:00:00Z")

  private def fixtures: cats.effect.IO[Unit] = {
    val periods = new PeriodRepositoryImpl(xa)
    val cats    = new CategoryRepositoryImpl(xa)
    for {
      _ <- periods.create(Period(period1, at, None))
      _ <- periods.create(Period(period2, at, None))
      _ <- cats.create(Category(catA, "Groceries", None))
      _ <- cats.create(Category(catB, "Rent", None))
    } yield ()
  }

  "upsert then findByPeriod returns the override" in {
    val repo = new CategoryBudgetOverrideRepositoryImpl(xa)
    for {
      _     <- fixtures
      _     <- repo.upsert(period1, catA, 12345L, at)
      found <- repo.findByPeriod(period1)
    } yield found shouldBe Map(catA -> 12345L)
  }

  "upsert twice replaces the amount" in {
    val repo = new CategoryBudgetOverrideRepositoryImpl(xa)
    for {
      _     <- fixtures
      _     <- repo.upsert(period1, catA, 12345L, at)
      _     <- repo.upsert(period1, catA, 0L, at)
      found <- repo.findByPeriod(period1)
    } yield found shouldBe Map(catA -> 0L)
  }

  "overrides are scoped to their period" in {
    val repo = new CategoryBudgetOverrideRepositoryImpl(xa)
    for {
      _     <- fixtures
      _     <- repo.upsert(period1, catA, 500L, at)
      found <- repo.findByPeriod(period2)
    } yield found shouldBe empty
  }

  "delete removes only that period's override for that category" in {
    val repo = new CategoryBudgetOverrideRepositoryImpl(xa)
    for {
      _      <- fixtures
      _      <- repo.upsert(period1, catA, 500L, at)
      _      <- repo.upsert(period1, catB, 700L, at)
      _      <- repo.upsert(period2, catA, 900L, at)
      _      <- repo.delete(period1, catA)
      first  <- repo.findByPeriod(period1)
      second <- repo.findByPeriod(period2)
    } yield {
      first shouldBe Map(catB -> 700L)
      second shouldBe Map(catA -> 900L)
    }
  }

  "deleteByCategory removes the category's overrides across periods" in {
    val repo = new CategoryBudgetOverrideRepositoryImpl(xa)
    for {
      _      <- fixtures
      _      <- repo.upsert(period1, catA, 500L, at)
      _      <- repo.upsert(period2, catA, 900L, at)
      _      <- repo.upsert(period1, catB, 700L, at)
      _      <- repo.deleteByCategory(catA)
      first  <- repo.findByPeriod(period1)
      second <- repo.findByPeriod(period2)
    } yield {
      first shouldBe Map(catB -> 700L)
      second shouldBe empty
    }
  }
}
