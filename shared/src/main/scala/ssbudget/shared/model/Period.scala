package ssbudget.shared.model

import io.circe.Codec
import ssbudget.shared.json.StringId

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
}
