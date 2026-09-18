package ssbudget.backend.service

import cats.effect.IO
import cats.syntax.all.*
import ssbudget.backend.db.Repositories
import ssbudget.shared.api.CategorySummary
import ssbudget.shared.model.*

import java.time.{Instant, LocalDate, ZoneOffset}

/** Keeps `daily_budget_snapshots` current: one row per day holding the terms of the free-money calculation.
  *
  * Nothing here computes free money itself — [[DailyBudgetSnapshot.freeMoneyCents]] and `v_daily_budget` do that from the stored terms. What this
  * service answers is "what were the terms on day X", for today and for any day already past.
  *
  * The app suspends when idle, so there is no midnight tick to hang a daily job on. Instead [[ensureUpToDate]] runs off ordinary traffic and fills in
  * whatever it missed. With an empty table that same pass reconstructs the whole history back to the first period — there is no separate backfill.
  *
  * Reconstruction is exact in level and approximate in timing: balances are carried between bank syncs by the transaction ledger and category budgets
  * are recomputed from the transactions themselves, but a planned item records only the date of its LAST payment, so instalments step down once
  * rather than in stages. Rows say which they are via [[SnapshotSource]].
  */
class BudgetSnapshotService(repos: Repositories) {

  import BudgetSnapshotService.*

  /** Write today's row, then fill any day still missing between the first period and today. Safe to call as often as traffic arrives: today is
    * rewritten (the day is still moving), earlier days are written once and then left alone.
    */
  def ensureUpToDate: IO[Unit] =
    for {
      now  <- IO.realTimeInstant
      today = LocalDate.ofInstant(now, ZoneOffset.UTC)
      due  <- daysDue(today)
      _    <- IO.whenA(due.nonEmpty) {
                load(due, today, now).flatMap(_.traverse_(w => repos.dailyBudgetSnapshots.recordAll(due.map(snapshot(w, _)))))
              }
    } yield ()

  /** One day's terms, computed but not stored — what the tests and any ad-hoc check go through. `None` outside the recorded span. */
  def componentsAsOf(date: LocalDate): IO[Option[DailyBudgetSnapshot]] =
    for {
      now   <- IO.realTimeInstant
      today  = LocalDate.ofInstant(now, ZoneOffset.UTC)
      world <- if date.isAfter(today) then IO.pure(None) else load(List(date), today, now)
      // Before the first period there is nothing to measure, so say so rather than returning a row of zeros.
      inSpan = world.filter(w => w.periods.headOption.exists(p => !Period.startDay(p.startDate).isAfter(date)))
    } yield inSpan.map(snapshot(_, date))

  /** Today, plus every earlier day back to the first period that has no row yet. Today is always included: the day is still moving. */
  private def daysDue(today: LocalDate): IO[List[LocalDate]] =
    repos.periods.findAll.map(earliestDay).flatMap {
      // Nothing has started yet, so there is no budget to measure.
      case None           => IO.pure(Nil)
      case Some(firstDay) =>
        repos.dailyBudgetSnapshots
          .datesBetween(firstDay, today)
          .map(known => allDays(firstDay, today).filter(d => d == today || !known.contains(d)))
    }

  // --- the world, loaded once -----------------------------------------------------------------

  /** Everything the given days need, in a fixed number of queries rather than a few per day.
    *
    * The transaction reads are scoped to what those days actually consult, because the steady state — one pass every few minutes to rewrite today —
    * would otherwise keep paying the whole backfill's read cost, on a pool that is deliberately one connection wide.
    */
  private def load(days: List[LocalDate], today: LocalDate, now: Instant): IO[Option[World]] =
    repos.periods.findAll.flatMap { periods =>
      earliestDay(periods) match {
        case None    => IO.pure(Option.empty[World])
        case Some(_) =>
          val sorted      = periods.sortBy(_.startDate.toEpochMilli)
          val from        = days.min
          // Category spend is counted from the start of the period a day falls in, so the earliest day dictates how far back to read.
          val spendFrom   = sorted.findLast(p => !Period.startDay(p.startDate).isAfter(from)).fold(from)(p => Period.startDay(p.startDate))
          // Balances are carried through the ledger only for days in the PAST; today reads the account's live balance, so a pass that writes nothing
          // else has no use for it. This is the difference between the steady state scanning the whole transaction table and not touching it.
          val needsLedger = days.exists(_ != today)
          for {
            accounts   <- repos.accounts.findAll
            defs       <- repos.expenseDefinitions.findAll
            cats       <- repos.categories.findAll
            rates      <- repos.exchangeRates.findAll
            primaryOpt <- repos.currencySettings.findPrimary
            snaps      <- accounts.traverse(a => repos.balanceSnapshots.findByAccount(a.id).map(a.id -> _)).map(_.toMap)
            records    <- periods.traverse(p => repos.expenseRecords.findByPeriod(p.id).map(p.id -> _)).map(_.toMap)
            overrides  <- repos.categoryBudgetOverrides.findAllStamped
            // Category history reaches as far back as the bank data goes, not just to the first period: the monthly statistic behind every budget is
            // drawn from all of it.
            monthly    <- repos.bankTransactions.monthlySpendByCategory(Instant.EPOCH, endOfDay(today), includeInflows = true)
            dailyCat   <- repos.bankTransactions.dailyNetSpendByCategory(startOf(spendFrom), endOfDay(today))
            // Unbounded below on purpose: a day is carried from the nearest snapshot at or before it, and that snapshot can predate any bound derived
            // from the days being written.
            dailyAcc   <- if needsLedger then repos.bankTransactions.dailyNetByAccount(Instant.EPOCH, endOfDay(today)) else IO.pure(Nil)
            uidIndex   <- accountUidIndex
          } yield {
            val rateTable = RateTable(primaryOpt.map(_.code).getOrElse(Currency.PLN), rates)
            Some(
              World(
                today = today,
                capturedAt = now,
                periods = sorted,
                accounts = accounts,
                defs = defs,
                categories = cats,
                rates = rateTable,
                snapshots = snaps.view.mapValues(_.sortBy(_.recordedAt.toEpochMilli)).toMap,
                records = records.view.mapValues(_.map(r => r.expenseDefId -> r).toMap).toMap,
                overrides = overrides.map { case (p, c, cents, at) => (p, c) -> (cents, at) }.toMap,
                monthlyByCat = byBucket(monthly, rateTable)(ym => LocalDate.parse(ym + "-01")),
                dailyByCat = byDay(dailyCat, rateTable),
                // A card group's cards are several bank accounts feeding one app account, so re-key before grouping and they simply add up.
                dailyByAccount = byDay(dailyAcc.flatMap { case (uid, cur, d, c) => uidIndex.get(uid).map((_, cur, d, c)) }, rateTable),
              ),
            )
          }
      }
    }

  /** bank account uid -> the app account whose balance it moves, resolving a card-group member to the group's account. */
  private def accountUidIndex: IO[Map[String, AccountId]] =
    for {
      connections <- repos.bankConnections.findAll
      links       <- connections.traverse(c => repos.bankConnections.findLinksByConnection(c.id)).map(_.flatten)
      groups      <- repos.cardGroups.findAll
    } yield {
      val groupAccount = groups.flatMap(g => g.accountId.map(g.id -> _)).toMap
      links.flatMap { link =>
        link.target match {
          case BankLinkTarget.Account(id)         => Some(link.ebAccountUid -> id)
          case BankLinkTarget.CardGroupMember(id) => groupAccount.get(id).map(link.ebAccountUid -> _)
          case BankLinkTarget.Unlinked            => None
        }
      }.toMap
    }

  // --- one day --------------------------------------------------------------------------------

  private def snapshot(w: World, day: LocalDate): DailyBudgetSnapshot = {
    val period   = w.periodOn(day)
    val balances = w.accounts.map(a => a -> balanceOf(w, a, day))

    def total(role: AccountRole): Long =
      balances.collect { case (a, Some(cents)) if a.role == role => w.rates.convert(cents, a.currency, day) }.sum

    val (toPay, toReceive) = plannedRemaining(w, period, day)
    val budgets            = period.toList.flatMap(p => budgetRemainings(w, p, day))

    DailyBudgetSnapshot(
      onDate = day,
      capturedAt = w.capturedAt,
      periodId = period.map(_.id),
      currency = w.rates.primary,
      spendableCents = total(AccountRole.Spending),
      plannedToPayCents = toPay,
      plannedToReceiveCents = toReceive,
      budgetsToSpendCents = budgets.filter(_ > 0).sum,
      budgetsToReceiveCents = -budgets.filter(_ < 0).sum,
      savingsCents = total(AccountRole.Savings),
      daysRemaining = period.map(p => Period.daysRemaining(p.startDate, day)),
      source = if day == w.today then SnapshotSource.Live else SnapshotSource.Reconstructed,
    )
  }

  /** An account's balance at the end of `day`, in its own currency.
    *
    * Today is simply what the account says. For a past day, the nearest balance snapshot is carried to that day through the transaction ledger —
    * forwards when the snapshot precedes the day, backwards when the only snapshots are later. Balances are recorded on sync, so most days have no
    * snapshot of their own, and it is the ledger that makes them knowable rather than flat. An account with no snapshots at all (and so no history to
    * anchor to) contributes nothing rather than a guess.
    */
  private def balanceOf(w: World, account: Account, day: LocalDate): Option[Long] =
    if day == w.today then Some(account.balanceCents)
    else {
      val history                                         = w.snapshots.getOrElse(account.id, Nil)
      val ledger                                          = w.dailyByAccount.getOrElse(account.id, Map.empty)
      // Transactions dated after the snapshot's own day, through `to`. A snapshot is taken mid-day while bank entries are stamped midnight, so the
      // snapshot's day counts as already reflected in it.
      def movement(after: LocalDate, to: LocalDate): Long =
        ledger.collect { case (d, cents) if d.isAfter(after) && !d.isAfter(to) => cents }.sum

      history.findLast(s => !dayOf(s.recordedAt).isAfter(day)) match {
        case Some(before) => Some(before.amount + movement(dayOf(before.recordedAt), day))
        case None         => history.headOption.map(first => first.amount - movement(day, dayOf(first.recordedAt)))
      }
    }

  /** (still to pay, still to receive) across the hand-declared items of the period `day` falls in, in the primary currency.
    *
    * Driven by the period's RECORDS rather than by the definition list: a record is opened for every definition when a period starts, and for a new
    * definition in the period it was created in, so "has a record in this period" is already the app's own statement of "existed then". Reading the
    * definitions directly would charge every past period with items invented since.
    */
  private def plannedRemaining(w: World, period: Option[Period], day: LocalDate): (Long, Long) = {
    val byDef                  = period.fold(Map.empty[ExpenseDefId, ExpenseRecord])(p => w.records.getOrElse(p.id, Map.empty))
    val amounts                = w.defs.flatMap { d =>
      byDef.get(d.id).map(r => d.itemType -> w.rates.convert(r.remainingAsOf(d.estimateCents, day), d.currency, day))
    }
    def sum(t: BudgetItemType) = amounts.collect { case (`t`, cents) => cents }.sum
    (sum(BudgetItemType.PlannedExpense), sum(BudgetItemType.PlannedIncome))
  }

  /** Signed remaining per category budget on `day`: positive still to be spent, negative still to arrive.
    *
    * Built as a [[CategorySummary]] so the figure goes through exactly the rules the Transactions page and the Dashboard use — the budget statistic,
    * the direction convention, and the per-type drawdown. `lastPeriodSpentCents` feeds none of them, so it is left at zero.
    *
    * The budget CONFIGURATION is whatever the category holds today; `categories` keeps one current method and window, with no history. A category
    * whose rule changed in June is therefore scored by the June rule all the way back.
    */
  private def budgetRemainings(w: World, period: Period, day: LocalDate): List[Long] = {
    val periodStart       = Period.startDay(period.startDate)
    val elapsed           = Period.elapsedFraction(period.startDate, day)
    // The window ends at the last COMPLETED month, which CategoryBudget.inWindow already enforces — the month `day` falls in never counts.
    val lastCompleteMonth = CategoryBudget.lastCompleteMonthIndex(day)
    w.categories.filter(_.budgetType.isDefined).map { cat =>
      val spent = w.dailyByCat
        .getOrElse(cat.id, Map.empty)
        .collect { case (d, cents) if !d.isBefore(periodStart) && !d.isAfter(day) => cents }
        .sum
      CategorySummary(
        category = cat,
        expectedMonthlyCents = cat.budget.expectedMonthly(w.monthlyByCat.getOrElse(cat.id, Map.empty), lastCompleteMonth),
        currentPeriodSpentCents = spent,
        lastPeriodSpentCents = 0L,
        currency = w.rates.primary,
        overrideRemainingCents = w.overrideOn(period.id, cat.id, day),
      ).remainingCents(elapsed)
    }
  }
}

object BudgetSnapshotService {

  private def dayOf(instant: Instant): LocalDate = LocalDate.ofInstant(instant, ZoneOffset.UTC)
  private def startOf(day: LocalDate): Instant   = day.atStartOfDay(ZoneOffset.UTC).toInstant
  private def endOfDay(day: LocalDate): Instant  = startOf(day.plusDays(1)).minusMillis(1)

  private def allDays(from: LocalDate, to: LocalDate): List[LocalDate] =
    Iterator.iterate(from)(_.plusDays(1)).takeWhile(!_.isAfter(to)).toList

  /** The first day any budget was being kept: the earliest period's start. */
  private def earliestDay(periods: List[Period]): Option[LocalDate] =
    periods.minByOption(_.startDate.toEpochMilli).map(p => Period.startDay(p.startDate))

  /** `(key, currency, bucket, cents)` rows to `key -> bucket -> cents in the primary currency`, each amount converted at the rate in force for its
    * own bucket.
    *
    * Converting here rather than at the point of use is what keeps a mixed-currency category counted in full — the same thing
    * [[CategorySummaryService]] does for the live figures.
    */
  private def byBucket[K](rows: List[(K, Currency, String, Long)], rates: RateTable)(dateOf: String => LocalDate): Map[K, Map[String, Long]] =
    rows
      .groupBy(_._1)
      .view
      .mapValues(_.groupMapReduce(_._3) { case (_, cur, bucket, cents) => rates.convert(cents, cur, dateOf(bucket)) }(_ + _))
      .toMap

  private def byDay[K](rows: List[(K, Currency, String, Long)], rates: RateTable): Map[K, Map[LocalDate, Long]] =
    byBucket(rows, rates)(LocalDate.parse).view.mapValues(_.map { case (day, cents) => LocalDate.parse(day) -> cents }).toMap

  /** Conversion into the primary currency at the rate in force on a given day.
    *
    * Rates are fetched a handful of times a year, so a historical day takes the most recent one at or before it, falling back to the earliest on
    * record for anything older still. The spending accounts are overwhelmingly in the primary currency, so what this smooths over is small.
    */
  final class RateTable(val primary: Currency, toPrimary: Map[Currency, List[ExchangeRate]]) {

    def convert(cents: Long, currency: Currency, day: LocalDate): Long =
      if currency == primary then cents
      else
        toPrimary
          .get(currency)
          .flatMap(rs => rs.findLast(!_.fetchedAt.isAfter(endOfDay(day))).orElse(rs.headOption))
          .map(_.convert(Money(cents, currency)).amountCents)
          .getOrElse(cents)
  }

  object RateTable {
    def apply(primary: Currency, rates: List[ExchangeRate]): RateTable =
      new RateTable(primary, rates.filter(_.toCurrency == primary).groupBy(_.fromCurrency).view.mapValues(_.sortBy(_.fetchedAt.toEpochMilli)).toMap)
  }

  /** Everything the per-day computation reads, loaded once per pass. */
  final private case class World(
      today: LocalDate,
      capturedAt: Instant,
      periods: List[Period],                               // oldest first
      accounts: List[Account],
      defs: List[BudgetItemDefinition],
      categories: List[Category],
      rates: RateTable,
      snapshots: Map[AccountId, List[BalanceSnapshot]],    // oldest first
      records: Map[PeriodId, Map[ExpenseDefId, ExpenseRecord]],
      overrides: Map[(PeriodId, CategoryId), (Long, Instant)],
      monthlyByCat: Map[CategoryId, Map[String, Long]],    // "YYYY-MM" -> net spend
      dailyByCat: Map[CategoryId, Map[LocalDate, Long]],   // day -> net spend
      dailyByAccount: Map[AccountId, Map[LocalDate, Long]], // day -> signed movement
  ) {

    /** The period `day` falls in: the last one that had started by then. Periods are contiguous, so that is also the one that had not yet ended. */
    def periodOn(day: LocalDate): Option[Period] =
      periods.findLast(p => !Period.startDay(p.startDate).isAfter(day))

    /** A manual override counts from the day it was typed, not for the whole period retrospectively. */
    def overrideOn(periodId: PeriodId, categoryId: CategoryId, day: LocalDate): Option[Long] =
      overrides.get((periodId, categoryId)).collect { case (cents, at) if !dayOf(at).isAfter(day) => cents }
  }
}
