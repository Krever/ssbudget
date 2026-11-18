package ssbudget.shared.api

import io.circe.Codec
import ssbudget.shared.model.*

import java.time.LocalDate

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
    savingsEndCents: Option[Long], // savings balance at period end, at today's exchange rates; None when no savings account has a record
    // The investment accounts. Their balance change has two parts. `investmentMovedCents` is the net amount moved into them (negative = withdrawn),
    // from the bank transactions of their transfer categories: that part is saving. The rest is market result, and it is never added to what was saved.
    investmentChangeCents: Long,
    investmentMovedCents: Long,
    investmentEndCents: Option[Long],
    endBalanceCents: Option[Long], // spending balance at period end, from snapshots; None when nothing was recorded that early
    topCategories: List[PeriodCategorySpend],
) derives Codec.AsObject {

  /** What the bank says the period netted: money in minus money out. Positive = the balance grew. */
  def netCents: Long = inflowCents - outflowCents

  /** What was saved in the period: the change of the savings accounts, plus what was moved into the investment accounts. */
  def savedCents: Long = savingsChangeCents + investmentMovedCents

  /** What the market did to the investment accounts: their balance change less what was moved. */
  def investmentMarketCents: Long = investmentChangeCents - investmentMovedCents

  def ongoing: Boolean = period.endDate.isEmpty
}

/** A category's gross outflow within one period (refunds do NOT subtract here — this ranks where the money went). */
final case class PeriodCategorySpend(
    category: Category,
    spentCents: Long,
) derives Codec.AsObject

/** Correct when a period is expected to end — the paycheck came early, came late, or the pay cycle changed shape. The date is stored on the period,
  * so this is the only thing that decides its length; nothing re-derives it from a payday.
  */
final case class SetPeriodExpectedEndRequest(expectedEnd: LocalDate) derives Codec.AsObject

/** The daily record behind the free-money charts.
  *
  * @param causes
  *   for each day of the OPEN period after its first: what moved the plan's own line. A closed period has none: its days keep the plan they were
  *   recorded with, and a breakdown by today's budgets would not add up to them.
  */
final case class FreeMoneyHistory(snapshots: List[DailyBudgetSnapshot], causes: List[DayCauses]) derives Codec.AsObject

/** The amount to move to savings in a period, in the primary currency. Zero removes the plan. */
final case class SetPeriodPlannedSavingRequest(plannedSavingCents: Long) derives Codec.AsObject
