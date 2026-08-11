package ssbudget.shared.api

import io.circe.Codec
import ssbudget.shared.model.*

/** One period's retrospective: what actually moved between the paycheck that opened it and the one that closed it. Everything monetary is in
  * [[currency]] (the primary currency), converted at the latest rates.
  *
  * Two independent views of the same period, deliberately kept apart rather than reconciled:
  *   - what the BANK saw — [[inflowCents]] / [[outflowCents]], summed from non-internal transactions booked in the period's window (transfers between
  *     own accounts excluded, so moving money to savings is neither income nor spend). Only as complete as the connected accounts: cash and
  *     unconnected banks are invisible here.
  *   - what the PLAN said — [[plannedPaidCents]] / [[incomeReceivedCents]] / [[plannedEstimateCents]], from the period's expense records. These
  *     overlap with the bank figures (rent paid by transfer shows up in both); they answer "did I stick to the plan?", not "where did the money go?".
  *
  * [[plannedEstimateCents]] uses each item's CURRENT estimate — definitions aren't versioned, so editing an estimate today shifts what past periods
  * appear to have planned. Paid amounts are per-period and therefore historically accurate.
  */
final case class PeriodSummary(
    period: Period,
    days: Int,                     // full length for a closed period, elapsed so far for the running one
    currency: Currency,
    inflowCents: Long,             // non-internal credits booked in the window
    outflowCents: Long,            // non-internal debits booked in the window, positive
    plannedPaidCents: Long,        // Σ paid against planned EXPENSES this period
    plannedEstimateCents: Long,    // Σ estimate of those same expenses (see the caveat above)
    incomeReceivedCents: Long,     // Σ received against planned INCOMES this period
    plannedSettled: Int,           // planned expenses closed this period
    plannedTotal: Int,             // planned expenses tracked this period
    savingsChangeCents: Long,      // savings balances at period end − at period start (+ saved / − withdrawn)
    endBalanceCents: Option[Long], // spending balance at period end, from snapshots; None when nothing was recorded that early
    topCategories: List[PeriodCategorySpend],
) derives Codec.AsObject {

  /** What the bank says the period netted: money in minus money out. Positive = the balance grew. */
  def netCents: Long = inflowCents - outflowCents

  def ongoing: Boolean = period.endDate.isEmpty
}

/** A category's gross outflow within one period (refunds do NOT subtract here — this ranks where the money went). */
final case class PeriodCategorySpend(
    category: Category,
    spentCents: Long,
) derives Codec.AsObject
