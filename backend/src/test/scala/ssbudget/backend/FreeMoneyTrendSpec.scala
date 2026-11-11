package ssbudget.backend

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import ssbudget.shared.model.*

import java.time.{Instant, LocalDate}

/** The projection of free money to a period's end: the current value, plus the median daily change of the plan's own line for each remaining day.
  *
  * The plan's own line is free money without savings transfers and unplanned income. Those are steps that say nothing about the plan.
  */
class FreeMoneyTrendSpec extends AnyFreeSpec with Matchers {

  private def day(s: String): LocalDate = LocalDate.parse(s)

  /** A 30-day period: June 1 to July 1. */
  private val period = Period(PeriodId("p"), Instant.parse("2026-06-01T08:00:00Z"), day("2026-07-01"), None)

  /** Consecutive days from June 1, with no outside money. */
  private def daily(values: Long*): List[FreeMoneyPoint] =
    values.toList.zipWithIndex.map { case (cents, i) => FreeMoneyPoint(day("2026-06-01").plusDays(i), cents, cents, 0, 0, measured = true) }

  "The plan's own line" - {
    // Free money, then the plan's line. On June 3, 2,000 comes from savings: free money rises, the plan's line does not.
    val points = List(
      FreeMoneyPoint(day("2026-06-01"), 5000, 900, 0, 0, measured = true),
      FreeMoneyPoint(day("2026-06-02"), 4800, 700, 0, 0, measured = true),
      FreeMoneyPoint(day("2026-06-03"), 6700, 600, 2000, 0, measured = true),
      FreeMoneyPoint(day("2026-06-04"), 7100, 500, 1500, 1000, measured = true),
    )

    "gives the leak as its change across the period" in {
      FreeMoneyTrend.leakCents(points) shouldBe -400
    }

    "is drawn so that it ends on the real free money of the last day" in {
      FreeMoneyTrend.anchored(points).map(_._2) shouldBe List(7500, 7300, 7200, 7100)
    }

    "has a median daily change that a step from outside does not move" in {
      FreeMoneyTrend.medianDailyChange(points) shouldBe Some(-100.0)
    }
  }

  "The median daily change" - {
    "is not moved by one large bill" in {
      FreeMoneyTrend.medianDailyChange(daily(9000, 8900, 3900, 3800, 3700)) shouldBe Some(-100.0)
    }

    "divides a gap of several days by its length" in {
      val gap = List(
        FreeMoneyPoint(day("2026-06-01"), 1000, 1000, 0, 0, measured = true),
        FreeMoneyPoint(day("2026-06-05"), 600, 600, 0, 0, measured = true),
      )
      FreeMoneyTrend.medianDailyChange(gap) shouldBe Some(-100.0)
    }

    "is unknown with fewer than two days" in {
      FreeMoneyTrend.medianDailyChange(daily(1000)) shouldBe None
    }
  }

  "The projection" - {
    "adds the median daily change for each remaining day to the current free money" in {
      // Five days, June 1 to 5: 26 days left, at -100 a day.
      FreeMoneyTrend.project(period, daily(9000, 8900, 3900, 3800, 3700)) shouldBe
        Some(FreeMoneyProjection(day("2026-07-01"), perDayCents = -100, endCents = 3700 - 2600))
    }

    "starts from the real free money, with the outside money in it" in {
      val helped = daily(9000, 8900, 8800, 8700, 8600).init :+ FreeMoneyPoint(day("2026-06-05"), 13600, 8600, 5000, 0, measured = true)
      FreeMoneyTrend.project(period, helped).map(_.endCents) shouldBe Some(13600 - 2600)
    }

    "never goes above the current value" in {
      FreeMoneyTrend.project(period, daily(1000, 1100, 1200, 1300, 1400)) shouldBe
        Some(FreeMoneyProjection(day("2026-07-01"), perDayCents = 0, endCents = 1400))
    }

    "is absent with too few days" in {
      FreeMoneyTrend.project(period, daily(9000, 8900, 8800, 8700)) shouldBe None
    }

    "is absent when the period has no day left" in {
      val late = daily(9000, 8900, 8800, 8700).map(p => p.copy(day = p.day.plusDays(27))) :+
        FreeMoneyPoint(day("2026-07-01"), 8600, 8600, 0, 0, measured = true)
      FreeMoneyTrend.project(period, late) shouldBe None
    }
  }

  "Daily snapshots" - {
    "are grouped by period, oldest first, and days with no period are dropped" in {
      def snap(d: String, p: Option[String], spendable: Long, fromSavings: Long, source: SnapshotSource) =
        DailyBudgetSnapshot(day(d), Instant.EPOCH, p.map(PeriodId(_)), Currency.PLN, spendable, 100, 30, 0, 0, 0, -40, fromSavings, 7, None, source)

      FreeMoneyTrend.byPeriod(
        List(
          snap("2026-06-02", Some("p"), 900, 50, SnapshotSource.Live),
          snap("2026-06-01", Some("p"), 1000, 0, SnapshotSource.Reconstructed),
          snap("2026-05-31", None, 5, 0, SnapshotSource.Reconstructed),
        ),
      ) shouldBe Map(
        PeriodId("p") -> List(
          FreeMoneyPoint(day("2026-06-01"), 930, -117, 0, 7, measured = false),
          FreeMoneyPoint(day("2026-06-02"), 830, -167, 50, 7, measured = true),
        ),
      )
    }
  }
}
