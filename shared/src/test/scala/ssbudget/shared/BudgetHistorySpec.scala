package ssbudget.shared

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import ssbudget.shared.model.*

import java.time.{Instant, LocalDate}

/** The as-of rules of a period, which the daily budget snapshot reconstructs the past with.
  *
  * They take the day to measure from rather than reading the clock, because the same code serves today's live row and a row rebuilt for some day
  * months ago.
  */
class BudgetHistorySpec extends AnyFreeSpec with Matchers {

  private def day(d: String): LocalDate = LocalDate.parse(d)

  /** A half-month period: opened on the 30th, expected to close on the 15th. Its length comes from the stored date and nothing else — no payday is
    * inferred from the calendar.
    */
  private val period =
    Period(PeriodId("p1"), Instant.parse("2026-07-30T12:00:00Z"), day("2026-08-15"), None)

  "A period, measured as of a given day" - {
    "has its full span left on the day it started" in {
      period.daysRemaining(day("2026-07-30")) shouldBe 16
      period.elapsedFraction(day("2026-07-30")) shouldBe 0.0
    }

    "is half elapsed around the middle" in {
      period.elapsedFraction(day("2026-08-07")) shouldBe 0.5 +- 0.02
    }

    "has nothing left on the expected end" in {
      period.daysRemaining(day("2026-08-15")) shouldBe 0
      period.elapsedFraction(day("2026-08-15")) shouldBe 1.0
    }

    "goes negative once it overruns, but stays pinned at fully elapsed" in {
      period.daysRemaining(day("2026-08-20")) shouldBe -5
      period.elapsedFraction(day("2026-08-20")) shouldBe 1.0
    }

    "counts the day it started as day 1" in {
      period.dayOfPeriod(day("2026-07-30")) shouldBe 1
      period.dayOfPeriod(day("2026-08-01")) shouldBe 3
    }
  }
}
