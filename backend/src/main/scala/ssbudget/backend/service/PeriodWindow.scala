package ssbudget.backend.service

import ssbudget.shared.model.Period

import java.time.{Instant, LocalDate, ZoneOffset}

/** Where a period begins and ends, for every reader of the bank ledger and of the balance history. This is the ONE definition; a query, a report or a
  * daily term asks it and does not compute a boundary of its own.
  *
  * A period opens at an instant (the paycheck arrives, some time in a day) and a bank entry has a date and no time. So the ledger cannot be cut at
  * the instant. The rule is: **the day a period opens is of that period, in full**. From that one rule come the three views below.
  *
  *   - **The entries of the period** ([[holds]], [[from]], [[until]]): every entry from the first day up to, and not including, the day the period
  *     was closed (which is the first day of the next period). The period reports and the category figures read this.
  *   - **The balance at a boundary** ([[PeriodWindow.lastLedgerDayBefore]]): the balance at the end of the day BEFORE the boundary, so that
  *     `balance at end − balance at start` is the sum of exactly the entries of the period. `BalanceHistory.asOf` reads this.
  *   - **The movement since the baseline** ([[sinceBaseline]]): the entries from the day AFTER the first day. The free-money terms read this, and not
  *     the first view, because their start point is a balance that was MEASURED on the first day (with the paycheck in it), not the balance of the
  *     day before.
  *
  * Known limit, by nature of the data: a balance that was recorded ON a boundary day, before the boundary instant, is taken as it is. It can already
  * hold entries of that day, which the first view counts in the new period. For a savings account this moves nothing between figures (the two periods
  * share that balance). For an investment account, a deposit made on that day before the balance was recorded lands in the market result of the two
  * periods with opposite signs. To cut the balance by day too would be wrong for a manual account, whose balance the user types just before the
  * period is closed.
  *
  * @param firstDay
  *   the day the period opened (UTC)
  * @param closedOn
  *   the day the period was closed, which is not a day of this period; None while the period is open
  */
final case class PeriodWindow(firstDay: LocalDate, closedOn: Option[LocalDate]) {

  /** Is `day` a day of the period? */
  def holds(day: LocalDate): Boolean = !day.isBefore(firstDay) && closedOn.forall(day.isBefore)

  /** The same range as instants, `[from, until)`, for a query on `booked_at`. */
  def from: Instant          = PeriodWindow.startOf(firstDay)
  def until: Option[Instant] = closedOn.map(PeriodWindow.startOf)

  /** Is `day` after the baseline day and not after `upTo`? See the third view above. */
  def sinceBaseline(day: LocalDate, upTo: LocalDate): Boolean = day.isAfter(firstDay) && !day.isAfter(upTo)
}

object PeriodWindow {

  def of(p: Period): PeriodWindow = PeriodWindow(p.startDay, p.endDate.map(dayOf))

  /** The last day whose entries are part of the balance at the boundary `at`: the day before. See the second view above. */
  def lastLedgerDayBefore(at: Instant): LocalDate = dayOf(at).minusDays(1)

  def dayOf(instant: Instant): LocalDate = LocalDate.ofInstant(instant, ZoneOffset.UTC)
  def startOf(day: LocalDate): Instant   = day.atStartOfDay(ZoneOffset.UTC).toInstant
}
