package ssbudget.backend

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import ssbudget.shared.model.Period

import java.time.{Instant, LocalDate}

/** The only thing still DERIVED about when a period ends: the date proposed when one is opened. A period stores its own expected end from then on, so
  * this rule is a starting point the user can correct, never the answer.
  *
  * It follows the day the period actually started rather than any fixed payday, so it fits a 25th, a 30th or anything else without being told.
  */
class PeriodSpec extends AnyFreeSpec with Matchers {

  private def proposed(startDate: String): LocalDate =
    Period.defaultExpectedEnd(Instant.parse(s"${startDate}T10:00:00Z"))

  "The expected end proposed for a new period" - {
    "is the same day of the following month, whatever day the period opened on" in {
      proposed("2026-07-25") shouldBe LocalDate.parse("2026-08-25")
      proposed("2026-07-30") shouldBe LocalDate.parse("2026-08-30")
      proposed("2026-07-15") shouldBe LocalDate.parse("2026-08-15")
      proposed("2026-12-30") shouldBe LocalDate.parse("2027-01-30") // across the year boundary
    }

    "clamps to the last day of a shorter month" in {
      proposed("2026-01-31") shouldBe LocalDate.parse("2026-02-28")
      proposed("2028-01-31") shouldBe LocalDate.parse("2028-02-29") // leap year
    }
  }
}
