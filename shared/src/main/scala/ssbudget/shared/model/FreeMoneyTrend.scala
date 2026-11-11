package ssbudget.shared.model

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Free money on one day of a period, with the money that reached the plan from outside it.
  *
  * @param cents
  *   free money as the Dashboard shows it
  * @param planLineCents
  *   see [[DailyBudgetSnapshot.planLineCents]]; only its change between two days has a meaning
  * @param fromSavingsCents
  *   see [[DailyBudgetSnapshot.fromSavingsCents]]
  * @param unplannedIncomeCents
  *   see [[DailyBudgetSnapshot.unplannedIncomeCents]]
  * @param measured
  *   `true` when the figure was recorded on the day, `false` when it was rebuilt from the record later.
  */
final case class FreeMoneyPoint(
    day: LocalDate,
    cents: Long,
    planLineCents: Long,
    fromSavingsCents: Long,
    unplannedIncomeCents: Long,
    measured: Boolean,
)

/** Where free money is expected to be on the period's expected end.
  *
  * @param perDayCents
  *   the usual daily change that is applied to each remaining day; never above zero
  */
final case class FreeMoneyProjection(endDay: LocalDate, perDayCents: Long, endCents: Long)

/** How free money moves during a period, and where that movement leads.
  *
  * Free money is a forecast of what is left when the period ends. A perfect plan gives a flat line, thus each movement of the line is an error of the
  * plan: spend that no budget covers, or a budget that is too low.
  *
  * The line that is read here is the plan's own line ([[FreeMoneyPoint.planLineCents]]). Raw free money also moves when money comes from savings or
  * an unplanned income arrives. Those movements are steps, they say nothing about the plan, and a slope through them points nowhere. Without them the
  * line falls steadily in each recorded period, and it can be extended.
  *
  * The method was selected by a test on the recorded periods: each candidate predicted the end of a period from each of its days. The median daily
  * change had about half the error of "no change". A straight line through the points was worse, because the large bills at the start of a period
  * make it too steep; the median does not see them.
  */
object FreeMoneyTrend {

  /** The smallest number of days that gives a projection. With fewer, the median is one or two days' accidents. */
  val MinDays = 5

  /** The daily points of each period, oldest first. Days with no open period are dropped. */
  def byPeriod(snapshots: List[DailyBudgetSnapshot]): Map[PeriodId, List[FreeMoneyPoint]] =
    snapshots
      .flatMap { s =>
        s.periodId.map { id =>
          id -> FreeMoneyPoint(
            s.onDate,
            s.freeMoneyCents,
            s.planLineCents,
            s.fromSavingsCents,
            s.unplannedIncomeCents,
            s.source == SnapshotSource.Live,
          )
        }
      }
      .groupMap(_._1)(_._2)
      .view
      .mapValues(_.sortBy(_.day.toEpochDay))
      .toMap

  /** The plan's own line, moved up or down so that its last day is at the real free money of that day.
    *
    * This is the line to draw. It ends on the figure the Dashboard shows, so a projection continues from it, and each earlier day shows where free
    * money would have been if the outside money of the full period had been there from the start.
    */
  def anchored(points: List[FreeMoneyPoint]): List[(LocalDate, Long)] =
    points.lastOption.fold(Nil)(last => points.map(p => p.day -> (last.cents - (last.planLineCents - p.planLineCents))))

  /** How much the plan lost between the first day and the last: the change of the plan's own line. Negative for a leak. */
  def leakCents(points: List[FreeMoneyPoint]): Long =
    points.headOption.zip(points.lastOption).fold(0L) { case (first, last) => last.planLineCents - first.planLineCents }

  /** The median change of the plan's own line from one day to the next. A gap of several days counts as one change, divided by its length. */
  def medianDailyChange(points: List[FreeMoneyPoint]): Option[Double] = {
    val changes = points
      .zip(points.drop(1))
      .collect { case (a, b) if b.day.isAfter(a.day) => (b.planLineCents - a.planLineCents).toDouble / ChronoUnit.DAYS.between(a.day, b.day) }
      .sorted
    Option.when(changes.nonEmpty) {
      val mid = changes.size / 2
      if changes.size % 2 == 1 then changes(mid) else (changes(mid - 1) + changes(mid)) / 2
    }
  }

  /** Projects the last point to the period's expected end: its free money, plus the median daily change for each remaining day.
    *
    * The projection never goes above the current value. Free money leaks; when the median day is a gain, the answer is "no change", not a rise. It
    * also assumes that no more money comes from outside the plan — that is a decision, not a trend.
    *
    * `None` when there are fewer than [[MinDays]] points, or no day left to project across.
    */
  def project(period: Period, points: List[FreeMoneyPoint]): Option[FreeMoneyProjection] =
    for {
      last    <- points.lastOption
      daysLeft = period.daysRemaining(last.day)
      if daysLeft > 0 && points.sizeIs >= MinDays
      median  <- medianDailyChange(points)
      perDay   = math.min(0L, math.round(median))
    } yield FreeMoneyProjection(period.expectedEnd, perDay, last.cents + perDay * daysLeft)

  /** The projected end of the period from each day, oldest first, with the usual daily change of the FULL record applied to each remaining day.
    *
    * The line moves only by the difference between a day and the usual day: it rises across days that were better than usual, falls across worse
    * days, and is flat when the period goes as projected. Its last entry is equal to the current projection.
    *
    * It is not the projection that each day in fact gave. That one used only the days before it, and a test on the recorded periods showed that its
    * day-to-day movement was as large as its error: it shows how the estimate settles, not how the period goes.
    *
    * Each day starts from its [[anchored]] value, thus money that came from outside the plan later in the period does not show as a step. Empty with
    * fewer than [[MinDays]] points.
    */
  def projectionHistory(period: Period, points: List[FreeMoneyPoint]): List[(LocalDate, Long)] =
    medianDailyChange(points).filter(_ => points.sizeIs >= MinDays).fold(Nil) { median =>
      val perDay = math.min(0L, math.round(median))
      anchored(points).map { case (day, cents) => day -> (cents + perDay * math.max(0, period.daysRemaining(day))) }
    }

  /** How much [[projectionHistory]] moved in the last `days` days: the day it is compared with, and the change. Positive says that these days were
    * better than the usual day of the period.
    *
    * When the history is shorter than `days`, its first entry is the comparison. `None` with fewer than two entries.
    */
  def projectionChange(history: List[(LocalDate, Long)], days: Int = 7): Option[(LocalDate, Long)] =
    history.lastOption.flatMap { case (lastDay, lastCents) =>
      val earlier = history.init
      earlier.filter { case (day, _) => !day.isAfter(lastDay.minusDays(days)) }.lastOption.orElse(earlier.headOption).map { case (day, cents) =>
        day -> (lastCents - cents)
      }
    }
}
