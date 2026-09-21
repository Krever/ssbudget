package ssbudget.shared.model

import io.circe.Codec
import ssbudget.shared.json.StringId

import java.time.temporal.ChronoUnit
import java.time.{Instant, LocalDate, ZoneOffset}

final case class PeriodId(value: String) extends AnyVal
object PeriodId                          extends StringId[PeriodId]

/** One budgeting window: from the paycheck that opened it to the one expected to close it.
  *
  * [[expectedEnd]] is STORED rather than inferred from a payday. Nothing here guesses when a period ends, so the same code serves a monthly cycle, a
  * twice-monthly one, or a paycheck that landed a week late — the date is simply corrected. [[Period.defaultExpectedEnd]] proposes one when a period
  * opens, and that is the whole of the app's opinion on the matter.
  *
  * Every as-of rule below takes the day to measure from rather than reading the clock, so the same code serves the live dashboard and a
  * reconstruction of some day months ago.
  *
  * A period is nonetheless expected to stay ROUGHLY A MONTH long. Category budgets derive a monthly figure and [[CategoryBudgetType]] draws it down
  * over one period without scaling it, so a deliberately half-length period reserves a whole month of variable spend against it. The old payday rule
  * enforced that bound as a side effect; a stored date does not, so it is now a convention this comment is the only statement of. Being paid twice a
  * month is modelled by leaving the period monthly and treating the second paycheck as a planned income, not by halving the period.
  *
  * @param endDate
  *   when the period was actually closed; `None` while it is running. An open period past its [[expectedEnd]] overruns — days remaining go negative
  *   rather than rolling to a next payday — so being late to close it stays visible instead of being silently absorbed.
  */
final case class Period(
    id: PeriodId,
    startDate: Instant,
    expectedEnd: LocalDate,
    endDate: Option[Instant],
) derives Codec.AsObject {

  /** The period's first calendar day (UTC). Bank data is date-granular, so the day the paycheck landed belongs to the period in full. */
  def startDay: LocalDate = Period.startDay(startDate)

  /** Days from `asOf` to [[expectedEnd]] — 0 on the expected day itself, negative once the period has overrun. */
  def daysRemaining(asOf: LocalDate): Int =
    ChronoUnit.DAYS.between(asOf, expectedEnd).toInt

  /** 1-based day of the period that `asOf` falls on, for "day N" labels. */
  def dayOfPeriod(asOf: LocalDate): Int =
    ChronoUnit.DAYS.between(startDay, asOf).toInt + 1

  /** 0..1 elapsed between the period's start and its [[expectedEnd]] as of `asOf`; pinned to 1 once overrun, 0 before it began.
    *
    * Measured against the EXPECTED end even for a period that has since closed, because it drives reconstructed snapshots: a past day is scored by
    * what was known on it, not by a date that only became true later.
    */
  def elapsedFraction(asOf: LocalDate): Double = {
    val total   = ChronoUnit.DAYS.between(startDay, expectedEnd).toDouble
    val elapsed = ChronoUnit.DAYS.between(startDay, asOf).toDouble
    if total <= 0 then 1.0 else math.max(0.0, math.min(1.0, elapsed / total))
  }
}

object Period {

  /** The expected end PROPOSED when a period opens: the same day of the following month, clamped when that month is shorter.
    *
    * Anchored to the day the period actually started, so it fits whatever payday rhythm is in use without being configured. It is only a proposal —
    * the period stores the date from then on, and correcting it is a field, not a rule change.
    */
  def defaultExpectedEnd(start: Instant): LocalDate = startDay(start).plusMonths(1)

  /** The calendar day (UTC) an instant falls on. Internal: callers outside hold a [[Period]] and ask it directly. */
  private def startDay(start: Instant): LocalDate = start.atZone(ZoneOffset.UTC).toLocalDate
}
