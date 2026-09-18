package ssbudget.backend.service

import cats.effect.IO
import cats.syntax.all.*
import ssbudget.backend.db.Repositories
import ssbudget.shared.api.CategorySummary
import ssbudget.shared.model.*

import java.time.Instant

/** Period boundaries and currency conversion — primitives that every figure the app reports is built on.
  *
  * They sit here rather than in the route file because three callers now need them: the HTTP handlers, the per-period retrospective, and the daily
  * budget snapshot, which asks the same questions about days long past.
  */
object BudgetQueries {

  /** The primary currency and a converter into it, at the latest rates: `(cents, currency) => cents in primary`. Anything missing a rate is passed
    * through unconverted (rare — the currency isn't enabled), which is the behaviour every caller here already relied on.
    *
    * Four earlier handlers open-code this same preamble; they are left alone deliberately, but new ones should use this.
    */
  def primaryConverter(repos: Repositories): IO[(Currency, (Long, Currency) => Long)] =
    for {
      primaryOpt <- repos.currencySettings.findPrimary
      enabled    <- repos.currencySettings.findAll
      primary     = primaryOpt.map(_.code).getOrElse(Currency.PLN)
      rateList   <- enabled.filterNot(_.code == primary).traverse(s => repos.exchangeRates.findLatest(s.code, primary))
    } yield {
      val rates                                       = rateList.flatten.map(r => r.fromCurrency -> r).toMap
      def toPrimary(cents: Long, cur: Currency): Long =
        if cur == primary then cents else rates.get(cur).map(_.convert(Money(cents, cur)).amountCents).getOrElse(cents)
      (primary, toPrimary)
    }

  /** The most recent closed period, i.e. the one that ended when the current period started. */
  def previousClosedPeriod(periods: List[Period]): Option[Period] =
    periods.filter(_.endDate.isDefined).sortBy(_.startDate.toEpochMilli).lastOption

  def startOfDayUtc(i: Instant): Instant =
    java.time.LocalDate.ofInstant(i, java.time.ZoneOffset.UTC).atStartOfDay(java.time.ZoneOffset.UTC).toInstant

  /** Start of a period's first calendar day (UTC). Bank `booked_at` is date-at-midnight, but a period starts at the paycheck instant (an afternoon
    * time), so comparing against the raw instant drops the whole start day's spend. Filtering from midnight of that day includes the start-day
    * transactions (bank data is date-granular, so we can't tell pre- vs post-paycheck spend anyway).
    */
  def periodStartOfDay(p: Period): Instant = startOfDayUtc(p.startDate)

  /** A period's `[from, to)` transaction window: midnight of its first day (see [[periodStartOfDay]]) up to, but excluding, midnight of the day it
    * ended (open-ended while it's still running). Used both to total a category's spend and to list the transactions behind that total, so the two
    * can't drift apart.
    */
  def periodWindow(p: Period): (Instant, Option[Instant]) = (periodStartOfDay(p), p.endDate.map(startOfDayUtc))
}

/** Per-category spend stats for the CURRENT period, as the Transactions page and the category budgets read them.
  *
  * Lifted out of the route file because [[BudgetSnapshotService]] builds the same [[CategorySummary]] for days in the past. Keeping the shape in one
  * place is what stops "what a budget still expects" from meaning two different things depending on who asked.
  */
class CategorySummaryService(repos: Repositories) {

  import BudgetQueries.*

  /** Per-category spend stats, converted to the primary currency at the latest rates (mixed-currency categories counted in full). Spend is NET
    * (outflows minus inflows) so pure-inflow categories (salary, refunds) aren't reported as 0 and refunds reduce a category's spend:
    *   - `expectedMonthlyCents` = the category's monthly figure, derived from its completed-month net spend the way its own budget method says (see
    *     [[CategoryBudget.expectedMonthly]]); the current partial month is excluded.
    *   - `currentPeriodSpentCents` = net spend since the current period started (from the start of that calendar day).
    *   - `lastPeriodSpentCents` = net spend over the previous (most recent closed) period; 0 if none.
    *   - `currency` = the primary currency.
    *   - `overrideRemainingCents` = the user's manual remaining-amount override for the current period, when set.
    *   - `monthlyHistory` = the recent completed months behind the figure, for the chart under a category's settings.
    */

  def summaries: IO[List[CategorySummary]] =
    for {
      cats        <- repos.categories.findAll
      periods     <- repos.periods.findAll
      converter   <- primaryConverter(repos)
      now          = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
      firstOfMonth = now.withDayOfMonth(1)
      currentMonth = firstOfMonth.atStartOfDay(java.time.ZoneOffset.UTC).toInstant
      currentOpt   = periods.find(_.endDate.isEmpty)
      prevOpt      = previousClosedPeriod(periods)
      periodStart  = currentOpt.map(periodStartOfDay).getOrElse(currentMonth)
      // NET spend (inflows subtract). All completed-month spend (current partial month excluded by `< currentMonth`), per (cat, currency, YYYY-MM).
      histRows    <- repos.bankTransactions.monthlySpendByCategory(java.time.Instant.EPOCH, currentMonth, includeInflows = true)
      curRows     <- repos.bankTransactions.spendByCategoryBetween(periodStart, None, includeInflows = true)
      prevRows    <- prevOpt match {
                       case Some(p) =>
                         val (from, to) = periodWindow(p)
                         repos.bankTransactions.spendByCategoryBetween(from, to, includeInflows = true)
                       case None    => IO.pure(List.empty[(CategoryId, Currency, Long)])
                     }
      // Manual remaining-amount overrides apply to the current period only.
      overrides   <- currentOpt.traverse(p => repos.categoryBudgetOverrides.findByPeriod(p.id)).map(_.getOrElse(Map.empty))
    } yield {
      // The month every lookback window ends on: `currentMonth` is the in-progress one, and the history stops just short of it.
      val lastCompleteMonth    = CategoryBudget.lastCompleteMonthIndex(now)
      val (primary, toPrimary) = converter

      // category -> (YYYY-MM -> primary-currency net spend, summed across currencies); only months that had activity are present.
      val byCatMonth                                                                = histRows
        .groupBy(_._1)
        .view
        .mapValues(rows => rows.groupBy(_._3).view.mapValues(_.map { case (_, cur, _, cents) => toPrimary(cents, cur) }.sum).toMap)
        .toMap
      def sumByCat(rows: List[(CategoryId, Currency, Long)]): Map[CategoryId, Long] =
        rows.groupBy(_._1).view.mapValues(_.map { case (_, cur, cents) => toPrimary(cents, cur) }.sum).toMap
      val curByCat                                                                  = sumByCat(curRows)
      val prevByCat                                                                 = sumByCat(prevRows)
      cats.map { cat =>
        val monthMap = byCatMonth.getOrElse(cat.id, Map.empty[String, Long])
        CategorySummary(
          cat,
          expectedMonthlyCents = cat.budget.expectedMonthly(monthMap, lastCompleteMonth),
          currentPeriodSpentCents = curByCat.getOrElse(cat.id, 0L),
          lastPeriodSpentCents = prevByCat.getOrElse(cat.id, 0L),
          currency = primary,
          overrideRemainingCents = overrides.get(cat.id),
          // The same months the statistic ran over, each already flagged with whether its window counted it.
          monthlyHistory = cat.budget.chartSeries(monthMap, lastCompleteMonth),
        )
      }
    }
}
