package ssbudget.backend

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import ssbudget.backend.service.PeriodWindow

import java.time.{Instant, LocalDate}

class PeriodWindowSpec extends AnyFreeSpec with Matchers {

  private def day(s: String) = LocalDate.parse(s)

  // A period that opened in the afternoon of 25 March and was closed in the morning of 24 April.
  private val window = PeriodWindow(day("2026-03-25"), Some(day("2026-04-24")))

  "the day a period opens is of that period in full, and the day it is closed is of the next one" in {
    window.holds(day("2026-03-24")) shouldBe false
    window.holds(day("2026-03-25")) shouldBe true
    window.holds(day("2026-04-23")) shouldBe true
    window.holds(day("2026-04-24")) shouldBe false
    window.from shouldBe Instant.parse("2026-03-25T00:00:00Z")
    window.until shouldBe Some(Instant.parse("2026-04-24T00:00:00Z"))
  }

  "an open period holds every day from its first day" in {
    val open = PeriodWindow(day("2026-04-24"), None)
    open.holds(day("2030-01-01")) shouldBe true
    open.until shouldBe None
  }

  "two periods in sequence share no day and leave no day out" in {
    val next = PeriodWindow(day("2026-04-24"), None)
    val days = Iterator.iterate(day("2026-03-20"))(_.plusDays(1)).takeWhile(_.isBefore(day("2026-05-01"))).toList
    days.filter(d => !d.isBefore(window.firstDay)).foreach(d => (window.holds(d), next.holds(d)) should (be((true, false)) or be((false, true))))
  }

  "the balance at a boundary is the balance at the end of the day before, so the change of a period is the sum of its entries" in {
    PeriodWindow.lastLedgerDayBefore(Instant.parse("2026-03-25T14:30:00Z")) shouldBe day("2026-03-24")
    PeriodWindow.lastLedgerDayBefore(Instant.parse("2026-04-24T08:00:00Z")) shouldBe day("2026-04-23")
  }

  "the movement since the baseline starts on the day after the first day" in {
    window.sinceBaseline(day("2026-03-25"), upTo = day("2026-04-01")) shouldBe false
    window.sinceBaseline(day("2026-03-26"), upTo = day("2026-04-01")) shouldBe true
    window.sinceBaseline(day("2026-04-02"), upTo = day("2026-04-01")) shouldBe false
  }
}
