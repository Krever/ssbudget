package ssbudget.backend

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import ssbudget.shared.model.Period

import java.time.{Instant, LocalDate}

/** The rule for when a period is expected to end: the next payday (the 25th) at least two weeks after its start. Anchored to the start — never to
  * today — so "days left" hits 0 on the payday itself and goes negative if the period isn't closed, instead of rolling to the next month's payday.
  */
class PeriodSpec extends AnyFreeSpec with Matchers {

  private def end(startDate: String): LocalDate =
    Period.expectedEnd(Instant.parse(s"${startDate}T10:00:00Z"))

  "A period's expected end" - {
    "is the following month's payday when the paycheck lands on the 25th" in {
      end("2026-07-25") shouldBe LocalDate.parse("2026-08-25")
    }

    "stays anchored to the following month when the paycheck lands a few days early" in {
      end("2026-07-23") shouldBe LocalDate.parse("2026-08-25") // 25th fell on a weekend, paid the Friday before
    }

    "stays anchored to the same cycle when the paycheck lands a few days late" in {
      end("2026-07-27") shouldBe LocalDate.parse("2026-08-25")
      end("2026-08-02") shouldBe LocalDate.parse("2026-08-25") // very late, already into the next month
    }

    "spans the year boundary" in {
      end("2026-12-25") shouldBe LocalDate.parse("2027-01-25")
    }
  }
}
