package ssbudget.shared.model

import io.circe.Codec
import ssbudget.shared.json.StringId

import java.time.{Instant, LocalDate, ZoneOffset}

final case class ExpenseRecordId(value: String) extends AnyVal
object ExpenseRecordId                          extends StringId[ExpenseRecordId]

/** One period's payment progress against a planned item. Payments accumulate into `paidAmount`, so a bill can be paid in instalments; `settled` is
  * the user saying "nothing more is coming this period", which is what actually closes the item. The two are independent on purpose: a bill that came
  * in cheaper than its estimate settles at the lower amount instead of leaving a residual, and a part-payment reduces what's still expected without
  * pretending the item is done.
  */
final case class ExpenseRecord(
    id: ExpenseRecordId,
    periodId: PeriodId,
    expenseDefId: ExpenseDefId,
    paidAmount: Option[Long], // in cents, accumulated across payments; None until anything is paid
    paidAt: Option[Instant],  // when the most recent payment was recorded; None until anything is paid
    settled: Boolean,         // no further payment expected this period
) derives Codec.AsObject {

  def paidCents: Long = paidAmount.getOrElse(0L)

  /** What's still expected this period against `estimateCents` — zero once settled, however little was actually paid. */
  def remaining(estimateCents: Long): Long =
    if settled then 0L else math.max(0L, estimateCents - paidCents)

  /** What was still expected against `estimateCents` as of the END of `asOf`, reconstructed from what the record now holds.
    *
    * The record keeps only its end state — an accumulated `paidAmount`, the instant of the LAST payment, and a `settled` flag with no timestamp at
    * all — so the one date available is `paidAt`. Before it, the item reads as untouched; from it on, as it stands today. An item paid in instalments
    * therefore steps down once, at the final payment, rather than in stages: the intermediate amounts were never recorded. Accurate for anything paid
    * in one go, which in practice is nearly everything.
    */
  def remainingAsOf(estimateCents: Long, asOf: LocalDate): Long =
    // Before the payment, the item reads as untouched. A settled record with no payment at all has no date to place it, so it counts as closed
    // throughout — there is nothing else it could mean.
    if paidAt.exists(_.atZone(ZoneOffset.UTC).toLocalDate.isAfter(asOf)) then estimateCents else remaining(estimateCents)

  /** Paid something, but not closed yet. */
  def isPartiallyPaid: Boolean = !settled && paidCents > 0

  /** Record a payment: `amountCents` ADDS to what was already paid this period, so instalments accumulate instead of overwriting. `settle` closes the
    * item. This is the single definition of that rule — the server route and the in-memory mock both go through it, so they can't drift.
    */
  def withPayment(amountCents: Long, at: Instant, settle: Boolean): ExpenseRecord =
    copy(paidAmount = Some(paidCents + amountCents), paidAt = Some(at), settled = settle)

  /** Back to untouched: nothing paid, nothing settled. */
  def cleared: ExpenseRecord = copy(paidAmount = None, paidAt = None, settled = false)
}

object ExpenseRecord {

  /** Remaining for an item whose record may not exist yet (no record = nothing paid, nothing settled). */
  def remainingFor(record: Option[ExpenseRecord], estimateCents: Long): Long =
    record.fold(estimateCents)(_.remaining(estimateCents))

  /** [[remainingFor]] as of a past day — see [[ExpenseRecord.remainingAsOf]]. */
  def remainingForAsOf(record: Option[ExpenseRecord], estimateCents: Long, asOf: LocalDate): Long =
    record.fold(estimateCents)(_.remainingAsOf(estimateCents, asOf))
}
