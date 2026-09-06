package ssbudget.shared.model

import io.circe.Codec

/** How a category's monthly figure is DERIVED: which statistic, over how much history, or a figure typed by hand.
  *
  * The three settings are one value rather than three fields on [[Category]] because they only mean anything together — a
  * [[CategoryBudgetMethod.Fixed]] method with no amount, or an amount no method reads, is a half-applied setting. Everything that derives a figure
  * hangs off here, so the server and the browser can't hold two readings of the same rule.
  *
  * Orthogonal to [[CategoryBudgetType]], which decides how the resulting figure is drawn down over a period: this picks the number, that spreads it.
  *
  * @param lookbackMonths
  *   completed months the statistic sees; `None` = everything on record
  * @param fixedCents
  *   the figure itself when the method is [[CategoryBudgetMethod.Fixed]]; signed, so a category whose money comes in takes a negative one
  */
final case class CategoryBudget(
    method: CategoryBudgetMethod = CategoryBudgetMethod.Average,
    lookbackMonths: Option[Int] = None,
    fixedCents: Option[Long] = None,
) derives Codec.AsObject {

  import CategoryBudget.*

  /** Whether this budget reads the category's transaction history at all. `false` means the figure is typed, which is why a lookback window means
    * nothing to it — the one rule for it, so [[expectedMonthly]] and the controls that render a figure can't disagree.
    */
  def derivesFromHistory: Boolean = method != CategoryBudgetMethod.Fixed

  /** As it should be stored: a window is clamped to [[maxLookbackMonths]] and a nonsensical one dropped, so a window that reaches past the history
    * anyone can see never gets written.
    */
  def normalized: CategoryBudget = copy(lookbackMonths = lookbackMonths.filter(_ > 0).map(_.min(maxLookbackMonths)))

  /** The expected monthly amount, in SIGNED primary-currency cents (negative for a category whose money comes in).
    *
    * A typed figure is returned verbatim. The statistics run over the months this budget's window admits — see [[CategoryBudget.densify]].
    *
    * The buckets are CALENDAR MONTHS rather than budget periods, even though the rest of the app is period-centric: spend is bucketed by
    * `substr(booked_at, 1, 7)` in SQL, and imported bank history reaches back long before the first `periods` row — so period buckets would throw
    * away most of the available history and would weigh periods of unequal length (23 to 40 days, in practice) against each other.
    */
  def expectedMonthly(monthMap: Map[String, Long], lastCompleteMonth: Int): Long = {
    lazy val values = densify(monthMap, lookbackMonths, lastCompleteMonth).map(_._2)
    method match {
      case CategoryBudgetMethod.Fixed   => fixedCents.getOrElse(0L)
      case CategoryBudgetMethod.Average => if values.isEmpty then 0L else values.sum / values.size
      case CategoryBudgetMethod.Median  => median(values)
    }
  }

  /** The series the UI charts: the most recent [[maxLookbackMonths]] months on record, each flagged with whether this budget's window counts it. A
    * budget with no window still averages over everything on record, which may reach further back than what is charted.
    */
  def chartSeries(monthMap: Map[String, Long], lastCompleteMonth: Int): List[MonthlySpend] =
    densify(monthMap, Some(maxLookbackMonths), lastCompleteMonth).map { case (index, cents) =>
      MonthlySpend(monthLabel(index), cents, included = inWindow(index, lookbackMonths, lastCompleteMonth))
    }

  /** This budget as one scannable line — "median 6", "avg", "fixed" — for a row that shows the settings without the controls. */
  def summary: String = {
    val window = lookbackMonths.filter(_ => derivesFromHistory).fold("")(n => s" $n")
    s"${CategoryBudgetMethod.shortLabel(method)}$window"
  }
}

object CategoryBudget {

  /** The furthest back a lookback window may reach, and so how many months of history travel with a summary for the chart to draw. Two years is
    * plenty to judge whether a category is stable; capping the window to the same number is what makes "the chart shows the whole window" true.
    */
  val maxLookbackMonths = 24

  /** Month index (year*12 + month), so calendar months compare and count as plain integers. */
  def monthIndex(year: Int, month: Int): Int = year * 12 + month

  /** [[monthIndex]] for a "YYYY-MM" bucket key. */
  def monthIndex(ym: String): Int =
    ym.split("-") match {
      case Array(y, m) => monthIndex(y.toInt, m.toInt)
      case _           => 0
    }

  /** The month every window ends on: the last COMPLETED calendar month before `today`. The in-progress month is never part of the history — half a
    * month of spend would drag every statistic down.
    */
  def lastCompleteMonthIndex(today: java.time.LocalDate): Int = monthIndex(today.getYear, today.getMonthValue) - 1

  /** The inverse of [[monthIndex]]: a "YYYY-MM" key for a month index. */
  private def monthLabel(index: Int): String = {
    val year  = (index - 1) / 12
    val month = index - year * 12
    f"$year%04d-$month%02d"
  }

  /** `monthMap` — completed-month net spend keyed "YYYY-MM", holding only months that had activity — narrowed to the window and then to the ACTIVE
    * SPAN inside it (first to last month WITH activity), with gap months filled in as zero and keyed by month index, oldest first.
    *
    * Leading and trailing empty months therefore never count, so a category that only started two months ago isn't diluted by a six-month window and
    * a dormant one isn't diluted by trailing zeros; interior gaps DO count, because a bill that skips a month is genuinely cheaper per month.
    */
  private def densify(monthMap: Map[String, Long], lookbackMonths: Option[Int], lastCompleteMonth: Int): List[(Int, Long)] = {
    val byIndex = monthMap.map { case (ym, cents) => monthIndex(ym) -> cents }.filter { case (i, _) =>
      inWindow(i, lookbackMonths, lastCompleteMonth)
    }
    if byIndex.isEmpty then Nil else (byIndex.keys.min to byIndex.keys.max).map(i => i -> byIndex.getOrElse(i, 0L)).toList
  }

  /** The one statement of the window: the `n` months ending at the last completed one, or everything up to it when no window is set. In `Long` so a
    * silly window can't underflow the bound.
    */
  private def inWindow(index: Int, lookbackMonths: Option[Int], lastCompleteMonth: Int): Boolean =
    lookbackMonths.filter(_ > 0).forall(n => index > lastCompleteMonth.toLong - n) && index <= lastCompleteMonth

  /** Middle value of a series; the mean of the two middle values when it has an even length, and 0 when it is empty. */
  private def median(xs: List[Long]): Long = {
    val sorted = xs.sorted
    val n      = sorted.size
    if n == 0 then 0L else if n % 2 == 1 then sorted(n / 2) else (sorted(n / 2 - 1) + sorted(n / 2)) / 2
  }
}
