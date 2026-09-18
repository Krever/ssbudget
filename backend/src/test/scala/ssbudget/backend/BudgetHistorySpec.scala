package ssbudget.backend

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import ssbudget.shared.model.*

import java.time.{Instant, LocalDate}

/** The two as-of rules the daily budget snapshot reconstructs the past with.
  *
  * Both take the day to measure from rather than reading the clock, because the same code serves today's live row and a row rebuilt for some day
  * months ago.
  */
class BudgetHistorySpec extends AnyFreeSpec with Matchers {

  private val start                     = Instant.parse("2026-07-24T12:00:00Z") // a period that runs to the payday on the 25th
  private def day(d: String): LocalDate = LocalDate.parse(d)

  "A period, measured as of a given day" - {
    "has its full span left on the day it started" in {
      Period.daysRemaining(start, day("2026-07-24")) shouldBe 32
      Period.elapsedFraction(start, day("2026-07-24")) shouldBe 0.0
    }

    "is half elapsed around the middle" in {
      Period.elapsedFraction(start, day("2026-08-09")) shouldBe 0.5 +- 0.02
    }

    "has nothing left on the expected payday" in {
      Period.daysRemaining(start, day("2026-08-25")) shouldBe 0
      Period.elapsedFraction(start, day("2026-08-25")) shouldBe 1.0
    }

    "goes negative once it overruns, but stays pinned at fully elapsed" in {
      Period.daysRemaining(start, day("2026-08-30")) shouldBe -5
      Period.elapsedFraction(start, day("2026-08-30")) shouldBe 1.0
    }

    "counts the day it started as day 1" in {
      Period.dayOfPeriod(start, day("2026-07-24")) shouldBe 1
      Period.dayOfPeriod(start, day("2026-07-26")) shouldBe 3
    }
  }

  "A planned item's remaining, as of a given day" - {
    val estimate = 50000L

    def record(paid: Option[Long], paidAt: Option[String], settled: Boolean) =
      ExpenseRecord(
        ExpenseRecordId("r1"),
        PeriodId("p1"),
        ExpenseDefId("d1"),
        paid,
        paidAt.map(d => Instant.parse(s"${d}T09:00:00Z")),
        settled,
      )

    "is the whole estimate before the item was paid" in {
      val settledOnThe10th = record(Some(estimate), Some("2026-08-10"), settled = true)
      settledOnThe10th.remainingAsOf(estimate, day("2026-08-09")) shouldBe estimate
    }

    "drops to zero from the day it was settled" in {
      val settledOnThe10th = record(Some(estimate), Some("2026-08-10"), settled = true)
      settledOnThe10th.remainingAsOf(estimate, day("2026-08-10")) shouldBe 0L
      settledOnThe10th.remainingAsOf(estimate, day("2026-08-20")) shouldBe 0L
    }

    "settles at the amount actually paid, not the estimate" in {
      val cheaperThanExpected = record(Some(30000L), Some("2026-08-10"), settled = true)
      cheaperThanExpected.remainingAsOf(estimate, day("2026-08-11")) shouldBe 0L
    }

    "leaves the remainder outstanding after a part-payment" in {
      val halfPaid = record(Some(20000L), Some("2026-08-10"), settled = false)
      halfPaid.remainingAsOf(estimate, day("2026-08-09")) shouldBe estimate
      halfPaid.remainingAsOf(estimate, day("2026-08-11")) shouldBe 30000L
    }

    "counts an untouched item in full on every day" in {
      val untouched = record(None, None, settled = false)
      untouched.remainingAsOf(estimate, day("2026-08-01")) shouldBe estimate
      ExpenseRecord.remainingForAsOf(None, estimate, day("2026-08-01")) shouldBe estimate
    }

    "counts a settled-but-never-paid item as closed throughout, having no date to place it" in {
      val settledUnpaid = record(None, None, settled = true)
      settledUnpaid.remainingAsOf(estimate, day("2026-08-01")) shouldBe 0L
    }
  }
}
