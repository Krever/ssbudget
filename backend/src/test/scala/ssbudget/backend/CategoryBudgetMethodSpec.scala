package ssbudget.backend

import java.time.LocalDate
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import ssbudget.shared.model.CategoryBudget.*
import ssbudget.shared.model.CategoryBudgetMethod.*
import ssbudget.shared.model.{CategoryBudget, CategoryBudgetMethod}

/** How a category's monthly figure is derived from its history. The companion piece to [[CategoryBudgetTypeSpec]]: this picks the number, that
  * spreads it over the period.
  */
class CategoryBudgetMethodSpec extends AnyFreeSpec with Matchers {

  /** "2026-08" is the last completed month throughout, so the history below sits in the six months up to it. */
  private val lastComplete = lastCompleteMonthIndex(LocalDate.of(2026, 9, 5))

  private val history = Map(
    "2026-03" -> 10000L,
    "2026-04" -> 12000L,
    "2026-05" -> 90000L, // the holiday month that drags a mean up
    "2026-06" -> 11000L,
    "2026-07" -> 13000L,
    "2026-08" -> 14000L,
  )

  private def estimate(
      method: CategoryBudgetMethod,
      months: Option[Int] = None,
      fixed: Option[Long] = None,
      hist: Map[String, Long] = history,
  ): Long = CategoryBudget(method, months, fixed).expectedMonthly(hist, lastComplete)

  private def charted(months: Option[Int], hist: Map[String, Long] = history) =
    CategoryBudget(Average, months).chartSeries(hist, lastComplete)

  "The last completed month is the one before today's" in {
    lastCompleteMonthIndex(LocalDate.of(2026, 9, 5)) shouldBe monthIndex("2026-08")
    lastCompleteMonthIndex(LocalDate.of(2026, 1, 31)) shouldBe monthIndex("2025-12")
  }

  "Average" - {
    "means the whole history when no window is set" in {
      estimate(Average) shouldBe 150000L / 6
    }

    "means only the window when one is set" in {
      estimate(Average, months = Some(3)) shouldBe (11000L + 13000L + 14000L) / 3 // Jun-Aug, the spike excluded
      estimate(Average, months = Some(1)) shouldBe 14000L
    }
  }

  "Median" - {
    "ignores the one month that doubles a mean" in {
      estimate(Median) shouldBe (12000L + 13000L) / 2 // the two middle months of six
      estimate(Median, months = Some(3)) shouldBe 13000L
    }

    "takes the middle month of an odd-length window" in {
      estimate(Median, months = Some(5)) shouldBe 13000L // 12000, 90000, 11000, 13000, 14000
    }
  }

  "Fixed" - {
    "returns the figure as typed, sign included, and never looks at history" in {
      estimate(Fixed, fixed = Some(50000L)) shouldBe 50000L
      estimate(Fixed, fixed = Some(-50000L)) shouldBe -50000L
      estimate(Fixed, fixed = Some(50000L), hist = Map.empty) shouldBe 50000L
    }

    "reads as zero until a figure is set" in {
      estimate(Fixed) shouldBe 0L
    }
  }

  "The window never dilutes a statistic with months the category didn't exist for" - {
    val young = Map("2026-07" -> 20000L, "2026-08" -> 30000L) // two months old, asked about six

    "so leading empty months don't count" in {
      estimate(Average, months = Some(6), hist = young) shouldBe 25000L
      estimate(Median, months = Some(6), hist = young) shouldBe 25000L
    }

    "and neither do trailing ones, for a category that has gone quiet" in {
      val dormant = Map("2026-03" -> 20000L, "2026-04" -> 30000L)
      estimate(Average, months = Some(6), hist = dormant) shouldBe 25000L
    }

    "but an interior gap month does — a bill that skips a month is cheaper per month" in {
      estimate(Average, hist = Map("2026-06" -> 30000L, "2026-08" -> 30000L)) shouldBe 20000L
    }
  }

  "Nothing on record reads as zero, whatever the method" in {
    estimate(Average, hist = Map.empty) shouldBe 0L
    estimate(Median, hist = Map.empty) shouldBe 0L
    estimate(Average, months = Some(2), hist = Map("2020-01" -> 99999L)) shouldBe 0L // all of it outside the window
  }

  "The charted series" - {
    "is labelled, oldest first, and fills interior gaps with zero so the chart shows what the mean saw" in {
      val gappy = Map("2026-06" -> 30000L, "2026-08" -> 30000L)
      charted(None, gappy).map(p => p.month -> p.cents) shouldBe List("2026-06" -> 30000L, "2026-07" -> 0L, "2026-08" -> 30000L)
    }

    "flags each month with whether the window counts it, so the chart can't disagree with the figure" in {
      charted(Some(2)).filter(_.included).map(_.month) shouldBe List("2026-07", "2026-08")
      charted(Some(2)).filterNot(_.included).map(_.month) shouldBe List("2026-03", "2026-04", "2026-05", "2026-06")
      // A blank window counts everything on record.
      charted(None).forall(_.included) shouldBe true
    }

    "labels months back across a year boundary" in {
      val acrossNewYear = Map("2025-11" -> 1L, "2025-12" -> 2L, "2026-01" -> 3L)
      CategoryBudget(Average).chartSeries(acrossNewYear, monthIndex("2026-01")).map(_.month) shouldBe
        List("2025-11", "2025-12", "2026-01")
    }

    "never reaches into the in-progress month" in {
      charted(None, history + ("2026-09" -> 99999L)).map(_.month).last shouldBe "2026-08"
    }
  }

  "An income category's figures stay negative all the way through" in {
    val incoming = Map("2026-06" -> -100000L, "2026-07" -> -300000L, "2026-08" -> -110000L)
    estimate(Average, hist = incoming) shouldBe -170000L
    estimate(Median, hist = incoming) shouldBe -110000L
  }
}
