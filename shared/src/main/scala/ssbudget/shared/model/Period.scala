package ssbudget.shared.model

import io.circe.Codec
import ssbudget.shared.json.StringId

import java.time.temporal.ChronoUnit
import java.time.{Instant, LocalDate, ZoneOffset}

final case class PeriodId(value: String) extends AnyVal
object PeriodId                          extends StringId[PeriodId]

final case class Period(
    id: PeriodId,
    startDate: Instant,
    endDate: Option[Instant], // None until period is closed
) derives Codec.AsObject

object Period {

  /** The assumed payday: a period nominally runs 25th → 25th. */
  private val paydayDayOfMonth = 25

  /** A paycheck can land a few days early or late; a payday closer to the start than this still belongs to the cycle the period opened. */
  private val minPeriodDays = 14

  /** Expected end of a period that started at `start` (UTC): the next payday at least [[minPeriodDays]] out. Anchored to the period's start — not to
    * today — so on the payday itself 0 days remain, and an unclosed period overruns instead of rolling to the next month's payday.
    */
  def expectedEnd(start: Instant): LocalDate = {
    val startDate = start.atZone(ZoneOffset.UTC).toLocalDate
    val sameMonth = startDate.withDayOfMonth(paydayDayOfMonth)
    if sameMonth.isBefore(startDate.plusDays(minPeriodDays)) then sameMonth.plusMonths(1) else sameMonth
  }

  /** The period's first calendar day (UTC). Bank data is date-granular, so the day the paycheck landed belongs to the period in full. */
  def startDay(start: Instant): LocalDate = start.atZone(ZoneOffset.UTC).toLocalDate

  /** Days from `asOf` to [[expectedEnd]] — 0 on the payday itself, negative once the period has overrun.
    *
    * Takes the day to measure from rather than reading the clock, so the same rule serves the live dashboard and a reconstruction of some day months
    * ago. Callers on the live path pass today.
    */
  def daysRemaining(start: Instant, asOf: LocalDate): Int =
    ChronoUnit.DAYS.between(asOf, expectedEnd(start)).toInt

  /** 1-based day of the period that `asOf` falls on, for "day N" labels. */
  def dayOfPeriod(start: Instant, asOf: LocalDate): Int =
    ChronoUnit.DAYS.between(startDay(start), asOf).toInt + 1

  /** 0..1 elapsed between the period's start and [[expectedEnd]] as of `asOf`; pinned to 1 once overrun, 0 before it began. */
  def elapsedFraction(start: Instant, asOf: LocalDate): Double = {
    val startDate = startDay(start)
    val total     = ChronoUnit.DAYS.between(startDate, expectedEnd(start)).toDouble
    val elapsed   = ChronoUnit.DAYS.between(startDate, asOf).toDouble
    if total <= 0 then 1.0 else math.max(0.0, math.min(1.0, elapsed / total))
  }
}
