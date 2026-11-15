package ssbudget.backend

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import ssbudget.backend.service.BalanceHistory
import ssbudget.shared.model.*

import java.time.{Instant, LocalDate}

/** The balance of an account at a past instant: a recorded balance where one exists, and the ledger before the first record.
  *
  * The Periods page and the Dashboard read the savings change of a period from this. Before, a period that began before the first record showed no
  * change at all, or a change counted only from the first record.
  */
class BalanceHistorySpec extends AnyFreeSpec with Matchers {

  private val acc = AccountId("savings")

  private def snap(at: String, cents: Long): BalanceSnapshot =
    BalanceSnapshot(BalanceSnapshotId(at), acc, cents, Currency.PLN, Instant.parse(at))

  private def day(d: String): LocalDate = LocalDate.parse(d)

  private def history(snaps: List[BalanceSnapshot], ledger: (String, Long)*): BalanceHistory =
    BalanceHistory(Map(acc -> snaps), Map(acc -> ledger.map { case (d, c) => day(d) -> c }.toMap))

  "A balance at an instant" - {
    "is the last record at or before it, and the ledger is not read" in {
      val h = history(List(snap("2026-07-09T14:00:00Z", 500_00), snap("2026-07-20T09:00:00Z", 700_00)), "2026-07-15" -> 999_00)
      h.asOf(acc, Instant.parse("2026-07-09T14:00:00Z")) shouldBe Some(500_00)
      h.asOf(acc, Instant.parse("2026-07-19T23:00:00Z")) shouldBe Some(500_00)
      h.asOf(acc, Instant.parse("2026-07-20T09:00:01Z")) shouldBe Some(700_00)
    }

    "before the first record is that record, less what the account moved after the day of the instant" in {
      // 100 came in on 1 July and 250 went out on 5 July; the balance was first recorded on 9 July as 500.
      val h = history(List(snap("2026-07-09T14:00:00Z", 500_00)), "2026-07-01" -> 100_00, "2026-07-05" -> -250_00)
      h.asOf(acc, Instant.parse("2026-07-06T10:00:00Z")) shouldBe Some(500_00)
      h.asOf(acc, Instant.parse("2026-07-04T10:00:00Z")) shouldBe Some(750_00)
      // An entry has a date and no time: the entries of the instant's own day count as before it.
      h.asOf(acc, Instant.parse("2026-07-01T10:00:00Z")) shouldBe Some(750_00)
      h.asOf(acc, Instant.parse("2026-06-30T10:00:00Z")) shouldBe Some(650_00)
    }

    "counts the entries of the first recorded day as already in the record" in {
      val h = history(List(snap("2026-07-09T14:00:00Z", 500_00)), "2026-07-09" -> 80_00)
      h.asOf(acc, Instant.parse("2026-07-08T10:00:00Z")) shouldBe Some(420_00)
      h.asOf(acc, Instant.parse("2026-07-09T10:00:00Z")) shouldBe Some(500_00)
    }

    "is anchored to the last record of the first recorded day, not to the opening zero of a new account" in {
      // An account made in the app starts at zero, and the first sync gives the real balance a moment later.
      val h = history(List(snap("2026-10-30T13:55:27Z", 0), snap("2026-10-30T13:55:28Z", 585_01)), "2026-10-27" -> 291_67)
      h.asOf(acc, Instant.parse("2026-10-30T07:47:00Z")) shouldBe Some(585_01)
      h.asOf(acc, Instant.parse("2026-10-01T07:47:00Z")) shouldBe Some(293_34)
    }

    "of an account with no transactions is its first record for all the time before it" in {
      val h = BalanceHistory(Map(acc -> List(snap("2026-07-09T14:00:00Z", 9352_00))), Map.empty)
      h.asOf(acc, Instant.parse("2026-01-29T22:00:00Z")) shouldBe Some(9352_00)
    }

    "is unknown for an account with no record at all" in {
      history(Nil, "2026-07-01" -> 100_00).asOf(acc, Instant.parse("2026-07-04T10:00:00Z")) shouldBe None
    }
  }
}
