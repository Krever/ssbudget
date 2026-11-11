package ssbudget.shared.model

import io.circe.Codec
import ssbudget.shared.json.EnumCodec

import java.time.{Instant, LocalDate}

/** Where a snapshot's figures came from: measured on the day, or rebuilt from the record afterwards. */
enum SnapshotSource {
  case Live, Reconstructed
}

object SnapshotSource {

  def asString(s: SnapshotSource): String = s match {
    case Live          => "live"
    case Reconstructed => "reconstructed"
  }

  def fromString(s: String): Either[String, SnapshotSource] = s match {
    case "live"          => Right(Live)
    case "reconstructed" => Right(Reconstructed)
    case other           => Left(s"Unknown snapshot source: $other")
  }

  given Codec[SnapshotSource] = EnumCodec(values, asString, "snapshot source")
}

/** One day's free-money TERMS, in the primary currency — the components, never the answer.
  *
  * Free money is `spendable + stillToReceive − stillToPay`. Persisting that one number would lose which term moved, so the terms are what's stored
  * and the arithmetic lives in `v_daily_budget` (and in [[freeMoneyCents]], which the tests hold the view to).
  *
  * @param savingsCents
  *   informational only. Moving money into a savings bucket already lowers `spendableCents`, so subtracting it again would double-count.
  * @param ledgerCents
  *   net of all movements on the spending accounts since the period's first day, by the bank ledger. It says what [[spendableCents]] did, but dated
  *   as the two terms below are dated: a balance is read at a sync, a transaction has the bank's booking date, and they are often a day apart.
  * @param fromSavingsCents
  *   money that reached the plan from outside the spending accounts since the period's first day: a transfer in from savings, or a budgeted bill paid
  *   directly from a savings account. Negative when money went to savings. Cumulative within the period.
  * @param unplannedIncomeCents
  *   income since the period's first day that no plan expected: the net inflow of each category that is not a budget, where that net is positive.
  *   Cumulative within the period.
  * @param daysRemaining
  *   to the period's expected end; `None` when no period was open that day.
  */
final case class DailyBudgetSnapshot(
    onDate: LocalDate,
    capturedAt: Instant,
    periodId: Option[PeriodId],
    currency: Currency,
    spendableCents: Long,
    plannedToPayCents: Long,
    plannedToReceiveCents: Long,
    budgetsToSpendCents: Long,
    budgetsToReceiveCents: Long,
    savingsCents: Long,
    ledgerCents: Long,
    fromSavingsCents: Long,
    unplannedIncomeCents: Long,
    daysRemaining: Option[Int],
    source: SnapshotSource,
) derives Codec.AsObject {

  /** Everything the plan still expects to leave the balance before the next paycheck. */
  def stillToPayCents: Long = plannedToPayCents + budgetsToSpendCents

  /** Everything it still expects to arrive. */
  def stillToReceiveCents: Long = plannedToReceiveCents + budgetsToReceiveCents

  /** What was actually free to spend that day. The Scala statement of the formula `v_daily_budget` repeats in SQL; BudgetSnapshotServiceSpec holds
    * the two to each other.
    */
  def freeMoneyCents: Long = spendableCents + stillToReceiveCents - stillToPayCents

  /** The level of the plan's own line: what the ledger moved, without the money from outside, set against what the plan still expects.
    *
    * Only its CHANGE from one day to the next has a meaning. It moves when the plan was wrong — spend that no budget covers, a budget that was too
    * low — and it does not move when money comes from savings or an unplanned income arrives. It is built from the ledger and not from
    * [[freeMoneyCents]], so that all its parts are dated the same way.
    */
  def planLineCents: Long = ledgerCents - fromSavingsCents - unplannedIncomeCents + stillToReceiveCents - stillToPayCents
}
