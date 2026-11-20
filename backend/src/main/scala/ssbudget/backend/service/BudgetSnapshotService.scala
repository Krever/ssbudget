package ssbudget.backend.service

import cats.effect.IO
import cats.syntax.all.*
import ssbudget.backend.db.Repositories
import ssbudget.backend.db.repository.DerivedTerms
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
  * are recomputed from the transactions themselves, but a manual override of a budget counts only from the day it was typed. Rows say which they are
  * via [[SnapshotSource]].
  *
  * The two `planned` terms of a row belonged to the hand-declared planned items, a feature that is removed. This service writes zero there. The days
  * of periods that closed before the removal keep the amounts they were recorded with.
  *
  * The days of the OPEN period follow the record and the configuration as they are now. On each pass, all terms of those days that are not balances
  * ([[DerivedTerms]]) are computed again, by the same rules as a reconstruction. Thus a changed budget, a transaction that got another category, or a
  * transaction that the bank sent late moves the line on the days it belongs to, and does not show as one bad day on the day it was typed or
  * imported. The balances of those days stay as they were measured. A closed period keeps what it was recorded with.
  */
class BudgetSnapshotService(repos: Repositories, clock: AppClock = AppClock.system) {

  import BudgetSnapshotService.*

  /** Write today's row, fill any day still missing between the first period and today, and derive the earlier days of the open period again. Safe to
    * call as often as traffic arrives: today is rewritten (the day is still moving), and an earlier day keeps its measurements.
    */
  def ensureUpToDate: IO[Unit] = pass.void

  /** [[ensureUpToDate]], and then, from the data that pass loaded: what moved the plan's own line on each day of the open period (see [[DayCauses]]).
    * The pass that runs off ordinary traffic does not pay for this; only the reader of the charts does.
    */
  def ensureUpToDateWithCauses: IO[List[DayCauses]] = pass.map(_.toList.flatMap(lineCauses))

  /** One pass over the table. Gives back what it loaded, so that a caller can read more from it with no second load. */
  private def pass: IO[Option[World]] =
    for {
      now     <- clock.now
      today    = LocalDate.ofInstant(now, ZoneOffset.UTC)
      periods <- repos.periods.findAll
      due     <- daysDue(periods, today)
      // The earlier days of the open period that already have a row. A day in `due` is written in full now, and needs no more.
      rederive = periods.find(_.endDate.isEmpty).toList.flatMap(open => allDays(open.startDay, today.minusDays(1))).filterNot(due.contains)
      // Rows of closed periods from before the outside-money terms existed. Those terms come from the transactions alone, so they can be given to an
      // old row without touching what was measured on its day.
      bare    <- repos.dailyBudgetSnapshots.datesWithoutOutsideTerms.map(_.toList.filterNot(d => due.contains(d) || rederive.contains(d)))
      days     = due ++ bare ++ rederive
      // Only the days that are written in full need balances. The other two groups read the plan and the period's transactions.
      world   <- if days.isEmpty then IO.pure(Option.empty[World]) else load(periods, days, balancesFor = due, today, now)
      _       <- world.traverse_ { w =>
                   repos.dailyBudgetSnapshots.recordAll(due.map(snapshot(w, _))) >>
                     repos.dailyBudgetSnapshots.fillOutsideTerms(bare.map { day =>
                       val (ledger, fromSavings, income) = outsideMoney(w, w.periodOn(day), day)
                       (day, ledger, fromSavings, income)
                     }) >>
                     repos.dailyBudgetSnapshots.rederive(rederive.map(day => derivedTerms(w, w.periodOn(day), day)))
                 }
    } yield world

  /** One day's terms, computed but not stored — what the tests and any ad-hoc check go through. `None` outside the recorded span. */
  def componentsAsOf(date: LocalDate): IO[Option[DailyBudgetSnapshot]] =
    for {
      now   <- clock.now
      today  = LocalDate.ofInstant(now, ZoneOffset.UTC)
      world <- if date.isAfter(today) then IO.pure(None)
               else repos.periods.findAll.flatMap(load(_, List(date), balancesFor = List(date), today, now))
      // Before the first period there is nothing to measure, so say so rather than returning a row of zeros.
      inSpan = world.filter(w => w.periods.headOption.exists(p => !p.startDay.isAfter(date)))
    } yield inSpan.map(snapshot(_, date))

  /** Today, plus every earlier day that has no row yet, back to where the record begins. Today is always included: the day is still moving.
    *
    * The record begins on its first row, and never grows backwards. A period that is added later for the time BEFORE that row (a backfill of the
    * period history) thus gets no daily rows: they could only be computed with the budgets of today, which says nothing about that time. With an
    * empty table the record begins at the first period.
    */
  private def daysDue(periods: List[Period], today: LocalDate): IO[List[LocalDate]] =
    earliestDay(periods) match {
      // Nothing has started yet, so there is no budget to measure.
      case None              => IO.pure(Nil)
      case Some(firstPeriod) =>
        for {
          recordStart <- repos.dailyBudgetSnapshots.earliestDate
          firstDay     = recordStart.filter(_.isAfter(firstPeriod)).getOrElse(firstPeriod)
          known       <- repos.dailyBudgetSnapshots.datesBetween(firstDay, today)
        } yield allDays(firstDay, today).filter(d => d == today || !known.contains(d))
    }

  // --- the world, loaded once -----------------------------------------------------------------

  /** Everything the given days need, in a fixed number of queries rather than a few per day.
    *
    * The transaction reads are scoped to what those days actually consult, because the steady state — one pass every few minutes to rewrite today —
    * would otherwise keep paying the whole backfill's read cost, on a pool that is deliberately one connection wide.
    */
  private def load(periods: List[Period], days: List[LocalDate], balancesFor: List[LocalDate], today: LocalDate, now: Instant): IO[Option[World]] = {
    earliestDay(periods) match {
      case None    => IO.pure(Option.empty[World])
      case Some(_) =>
        val sorted      = periods.sortBy(_.startDate.toEpochMilli)
        val from        = days.min
        // Category spend is counted from the start of the period a day falls in, so the earliest day dictates how far back to read.
        val spendFrom   = sorted.findLast(p => !p.startDay.isAfter(from)).fold(from)(_.startDay)
        // Balances are carried through the ledger only for days in the PAST; today reads the account's live balance, so a pass that writes nothing
        // else has no use for it. This is the difference between the steady state scanning the whole transaction table and not touching it.
        val needsLedger = balancesFor.exists(_ != today)
        for {
          accounts   <- repos.accounts.findAll
          cats       <- repos.categories.findAll
          rates      <- repos.exchangeRates.findAll
          primaryOpt <- repos.currencySettings.findPrimary
          snaps      <- accounts.traverse(a => repos.balanceSnapshots.findByAccount(a.id).map(a.id -> _)).map(_.toMap)
          overrides  <- repos.categoryBudgetOverrides.findAllStamped
          // Category history reaches as far back as the bank data goes, not just to the first period: the monthly statistic behind every budget is
          // drawn from all of it.
          monthly    <- repos.bankTransactions.monthlySpendByCategory(Instant.EPOCH, endOfDay(today), includeInflows = true)
          dailyCat   <- repos.bankTransactions.dailyNetSpendByCategory(startOf(spendFrom), endOfDay(today))
          // Unbounded below on purpose: a day is carried from the nearest snapshot at or before it, and that snapshot can predate any bound derived
          // from the days being written.
          dailyAcc   <- if needsLedger then repos.bankTransactions.dailyNetByAccount(Instant.EPOCH, endOfDay(today)) else IO.pure(Nil)
          uidIndex   <- BalanceHistory.accountUidIndex(repos)
          // From the start of the earliest period in play: the outside-money terms are cumulative within a period.
          movements  <- repos.bankTransactions.dailyNetByAccountAndCategory(startOf(spendFrom), endOfDay(today))
        } yield {
          val spending  = accounts.filter(_.role == AccountRole.Spending).map(_.id).toSet
          val holding   = accounts.filter(_.role == AccountRole.Holding).map(_.id).toSet
          val rateTable = RateTable(primaryOpt.map(_.code).getOrElse(Currency.PLN), rates)
          Some(
            World(
              today = today,
              capturedAt = now,
              periods = sorted,
              accounts = accounts,
              categories = cats,
              rates = rateTable,
              snapshots = snaps.view.mapValues(_.sortBy(_.recordedAt.toEpochMilli)).toMap,
              overrides = overrides.map { case (p, c, cents, at) => (p, c) -> (cents, at) }.toMap,
              monthlyByCat = byBucket(monthly, rateTable)(ym => LocalDate.parse(ym + "-01")),
              dailyByCat = byDay(dailyCat.map { case (cat, cur, d, cents, _, _) => (cat, cur, d, cents) }, rateTable),
              // Counts need no conversion, so the currency rows of a day simply add up.
              dailyCountsByCat = dailyCat
                .groupBy(_._1)
                .view
                .mapValues {
                  _.groupMapReduce(r => LocalDate.parse(r._3))(r => (r._5, r._6)) { case ((o1, i1), (o2, i2)) => (o1 + o2, i1 + i2) }
                }
                .toMap,
              // In the ACCOUNT's currency, unconverted: these movements are added to a balance that is itself in that currency, and the total is
              // converted once, afterwards. Converting here too would count a foreign movement at the square of its rate.
              dailyByAccount = BalanceHistory.ledgerByAccount(dailyAcc, uidIndex),
              movements = movements.map { m =>
                val day     = LocalDate.parse(m.day)
                val account = uidIndex.get(m.accountUid)
                Movement(
                  day,
                  onSpendingAccount = account.exists(spending),
                  onHoldingAccount = account.exists(holding),
                  m.category,
                  m.internal,
                  m.withHolding,
                  rateTable.convert(m.cents, m.currency, day),
                )
              },
            ),
          )
        }
    }
  }

  // --- one day --------------------------------------------------------------------------------

  private def snapshot(w: World, day: LocalDate): DailyBudgetSnapshot = {
    val period   = w.periodOn(day)
    val balances = w.accounts.map(a => a -> balanceOf(w, a, day))

    def total(role: AccountRole): Long =
      balances.collect { case (a, Some(cents)) if a.role == role => w.rates.convert(cents, a.currency, day) }.sum

    val derived = derivedTerms(w, period, day)

    DailyBudgetSnapshot(
      onDate = day,
      capturedAt = w.capturedAt,
      periodId = period.map(_.id),
      currency = w.rates.primary,
      spendableCents = total(AccountRole.Spending),
      // The planned items are removed. Their two terms stay in the row, because the days of old periods hold amounts there.
      plannedToPayCents = 0L,
      plannedToReceiveCents = 0L,
      budgetsToSpendCents = derived.budgetsToSpendCents,
      budgetsToReceiveCents = derived.budgetsToReceiveCents,
      savingsCents = total(AccountRole.Savings),
      ledgerCents = derived.ledgerCents,
      fromSavingsCents = derived.fromSavingsCents,
      unplannedIncomeCents = derived.unplannedIncomeCents,
      daysRemaining = period.map(_.daysRemaining(day)),
      source = if day == w.today then SnapshotSource.Live else SnapshotSource.Reconstructed,
      savingReserveCents = derived.savingReserveCents,
    )
  }

  /** All terms of `day` that are not balances: what a row holds apart from the readings of its day. */
  private def derivedTerms(w: World, period: Option[Period], day: LocalDate): DerivedTerms = {
    val budgets                       = period.toList.flatMap(p => budgetRemainings(w, p, day).map(_._2))
    val (ledger, fromSavings, income) = outsideMoney(w, period, day)
    val (toSpend, toArrive)           = (budgets.filter(_ > 0).sum, -budgets.filter(_ < 0).sum)
    // What went to savings is the opposite of what came from them. It is counted as the line counts it: net of the budgeted bills that were paid
    // from a savings account, so that money moved there only to pay such a bill is not taken for saving.
    val savingReserve                 = period.fold(0L)(_.savingReserveCents(savedCents = -fromSavings, stillToPayCents = toSpend))
    DerivedTerms(day, toSpend, toArrive, ledger, fromSavings, income, savingReserve)
  }

  /** For each day of the open period after its first: the parts of that day's change of the plan's own line, largest first.
    *
    * The parts follow the terms of [[DailyBudgetSnapshot.planLineCents]], so their sum is the change of the line:
    *   - a category with a budget: its movement of the day, less the change of what its budget still expects. A payment that the budget covers gives
    *     zero. A payment above the budget gives the excess. A quiet day of a steady budget gives back that day's share.
    *   - a category with no budget (no category is one such group): its spend on the spending accounts. While the group's net of the period is above
    *     zero it is unplanned income, which the line leaves out, and it gives nothing.
    *
    * Transfers between own accounts are not in the list: the line leaves them out.
    */
  private def lineCauses(w: World): List[DayCauses] =
    w.periods.find(_.endDate.isEmpty).toList.flatMap { p =>
      val days       = allDays(p.startDay, w.today)
      val names      = w.categories.map(c => c.id -> c.name).toMap
      val inPeriod   = w.movementsIn(p, w.today).filter(!_.internal)
      // What each budget still expects at the end of each day.
      val remaining  = days.map(d => d -> budgetRemainings(w, p, d).map { case (cat, cents) => cat.id -> cents }.toMap).toMap
      val budgetMove = inPeriod.filter(_.category.exists(w.budgeted)).groupMapReduce(m => (m.day, m.category))(_.cents)(_ + _)
      // The movements of each group with no budget, on the spending accounts.
      val unbudgeted = inPeriod.filter(w.isUnbudgeted).groupBy(_.category)

      days.zip(days.drop(1)).map { case (prev, day) =>
        val fromBudgets  = w.budgeted.toList.map { id =>
          val moved    = budgetMove.getOrElse((day, Some(id)), 0L)
          val expected = remaining(day).getOrElse(id, 0L) - remaining(prev).getOrElse(id, 0L)
          LineCause(names.getOrElse(id, id.value), moved - expected)
        }
        val fromNoBudget = unbudgeted.toList.map { case (category, moves) =>
          def lossTo(d: LocalDate) = math.min(0L, moves.filter(!_.day.isAfter(d)).map(_.cents).sum)
          LineCause(category.flatMap(names.get).getOrElse("No category"), lossTo(day) - lossTo(prev))
        }
        DayCauses(day, (fromBudgets ++ fromNoBudget).filter(_.cents != 0).sortBy(c => -math.abs(c.cents)))
      }
    }

  /** (ledger, from savings, unplanned income) accumulated in the period `day` falls in, up to `day`, in the primary currency.
    *
    * Both count from the day AFTER the period opened. The first day's row is the baseline that every later day is compared with, and the paycheck
    * that opened the period arrives on that day — it is the reason the period exists, not a surprise.
    *
    * Ledger: every movement on a spending account, of any kind.
    *
    * From savings:
    *   - an internal transfer seen on a spending account. Transfers between two spending accounts show both legs there and cancel; what is left came
    *     from, or went to, an account that is not a spending account.
    *   - a movement in a BUDGETED category on an account that is not a spending account. A bill paid directly from savings releases its budget while
    *     the spendable balance does not fall, so free money rises by the bill — with savings money.
    *
    * Unplanned income: for each category that is not a budget (no category counts as one such group), the net of its movements on the spending
    * accounts, where that net is positive. The net, not each inflow: a currency exchange filed under one category has an outflow and an inflow that
    * cancel, and a refund only makes its category's spend smaller. Which categories are budgets is today's configuration, as everywhere in this
    * service.
    */
  private def outsideMoney(w: World, period: Option[Period], day: LocalDate): (Long, Long, Long) =
    period.fold((0L, 0L, 0L)) { p =>
      val inPeriod                                              = w.movementsIn(p, day)
      val onSpending                                            = inPeriod.filter(_.onSpendingAccount)
      def budgetedSpendFrom(account: Movement => Boolean): Long =
        inPeriod.filter(m => account(m) && !m.internal && m.category.exists(w.budgeted)).map(_.cents).sum

      // Money from savings: internal transfers, and the bank side of an investment account (a transfer category). A transfer from or to a
      // holding account is not one of them: to put pay on hold, or to take it from there, is not saving.
      val transfers = onSpending.filter(m => (m.internal && !m.withHolding) || m.category.exists(w.transferCategories)).map(_.cents).sum
      val paidFor   = budgetedSpendFrom(m => !m.onSpendingAccount && !m.onHoldingAccount)

      // Pay that was on hold is income when it arrives on a spending account. It is also income, spent at once, when a budgeted bill is paid
      // directly from the holding account. Both are income that no budget expected, so the plan's own line leaves them out. Only a net arrival
      // counts, as for a category: money that goes ON hold is not a negative income.
      val released   = math.max(0L, onSpending.filter(m => m.internal && m.withHolding).map(_.cents).sum)
      val paidOnHold = budgetedSpendFrom(_.onHoldingAccount)
      val income     = inPeriod.filter(w.isUnbudgeted).groupMapReduce(_.category)(_.cents)(_ + _).values.filter(_ > 0).sum

      (onSpending.map(_.cents).sum, transfers - paidFor, income + released - paidOnHold)
    }

  /** An account's balance at the end of `day`, in its own currency.
    *
    * Today is simply what the account says. For a past day, the nearest balance snapshot is carried to that day through the transaction ledger —
    * forwards when the snapshot precedes the day, backwards when the only snapshots are later ([[BalanceHistory.carriedBack]]). Balances are recorded
    * on sync, so most days have no snapshot of their own, and it is the ledger that makes them knowable rather than flat. An account with no
    * snapshots at all (and so no history to anchor to) contributes nothing rather than a guess.
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
        case None         => BalanceHistory.carriedBack(history, ledger, day)
      }
    }

  /** Signed remaining per category budget on `day`: positive still to be spent, negative still to arrive.
    *
    * Built as a [[CategorySummary]] so the figure goes through exactly the rules the Transactions page and the Dashboard use — the budget statistic,
    * the direction convention, and the per-type drawdown. `lastPeriodSpentCents` feeds none of them, so it is left at zero.
    *
    * The budget CONFIGURATION is whatever the category holds today; `categories` keeps one current method and window, with no history. A category
    * whose rule changed in June is therefore scored by the June rule all the way back.
    */
  private def budgetRemainings(w: World, period: Period, day: LocalDate): List[(Category, Long)] = {
    val periodStart                                                                        = period.startDay
    val elapsed                                                                            = period.elapsedFraction(day)
    // The window ends at the last COMPLETED month, which CategoryBudget.inWindow already enforces — the month `day` falls in never counts.
    val lastCompleteMonth                                                                  = CategoryBudget.lastCompleteMonthIndex(day)
    // The category's per-day values from the period's start up to `day` — the period-to-date window every figure below is summed over.
    def toDate[A](byDay: Map[CategoryId, Map[LocalDate, A]], cat: CategoryId): Iterable[A] =
      byDay.getOrElse(cat, Map.empty).collect { case (d, a) if !d.isBefore(periodStart) && !d.isAfter(day) => a }
    w.categories.filter(_.budgetType.isDefined).map { cat =>
      val spent               = toDate(w.dailyByCat, cat.id).sum
      val (outflows, inflows) = toDate(w.dailyCountsByCat, cat.id).foldLeft((0, 0)) { case ((o, i), (o1, i1)) => (o + o1, i + i1) }
      cat -> CategorySummary(
        category = cat,
        expectedMonthlyCents = cat.budget.expectedMonthly(w.monthlyByCat.getOrElse(cat.id, Map.empty), lastCompleteMonth),
        currentPeriodSpentCents = spent,
        lastPeriodSpentCents = 0L,
        currency = w.rates.primary,
        overrideRemainingCents = w.overrideOn(period.id, cat.id, day),
        currentPeriodOutflowCount = outflows,
        currentPeriodInflowCount = inflows,
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
    periods.minByOption(_.startDate.toEpochMilli).map(_.startDay)

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

  /** One day's net movement on one bank account, in one category, in the primary currency. */
  final private case class Movement(
      day: LocalDate,
      onSpendingAccount: Boolean,
      onHoldingAccount: Boolean, // the account holds money that waits for the next period (see `AccountRole.Holding`)
      category: Option[CategoryId],
      internal: Boolean,
      withHolding: Boolean,      // the other side of the entries is a holding account
      cents: Long,
  )

  /** Everything the per-day computation reads, loaded once per pass. */
  final private case class World(
      today: LocalDate,
      capturedAt: Instant,
      periods: List[Period],                                         // oldest first
      accounts: List[Account],
      categories: List[Category],
      rates: RateTable,
      snapshots: Map[AccountId, List[BalanceSnapshot]],              // oldest first
      overrides: Map[(PeriodId, CategoryId), (Long, Instant)],
      monthlyByCat: Map[CategoryId, Map[String, Long]],              // "YYYY-MM" -> net spend
      dailyByCat: Map[CategoryId, Map[LocalDate, Long]],             // day -> net spend
      dailyCountsByCat: Map[CategoryId, Map[LocalDate, (Int, Int)]], // day -> (outflows, inflows)
      dailyByAccount: Map[AccountId, Map[LocalDate, Long]],          // day -> signed movement, in the account's currency
      movements: List[Movement],                                     // since the start of the earliest period in play
  ) {

    /** The categories that are budgets, by today's configuration. */
    lazy val budgeted: Set[CategoryId] = categories.filter(_.budgetType.isDefined).map(_.id).toSet

    /** The categories that stand for transfers to and from an investment account (see `Category.transferAccount`), by today's configuration. */
    lazy val transferCategories: Set[CategoryId] = categories.filter(_.transferAccount.isDefined).map(_.id).toSet

    /** The movements of period `p` up to `day`, from the day AFTER the period opened: the first day is the baseline, and the paycheck lands on it. */
    def movementsIn(p: Period, day: LocalDate): List[Movement] =
      movements.filter(m => PeriodWindow.of(p).sinceBaseline(m.day, day))

    /** Is this a movement of a group with no budget, on a spending account? Such a group is unplanned spend, or unplanned income. */
    def isUnbudgeted(m: Movement): Boolean =
      m.onSpendingAccount && !m.internal && !m.category.exists(budgeted) && !m.category.exists(transferCategories)

    /** The period `day` falls in: the last one that had started by then. Periods are contiguous, so that is also the one that had not yet ended. */
    def periodOn(day: LocalDate): Option[Period] =
      periods.findLast(p => !p.startDay.isAfter(day))

    /** A manual override counts from the day it was typed, not for the whole period retrospectively. */
    def overrideOn(periodId: PeriodId, categoryId: CategoryId, day: LocalDate): Option[Long] =
      overrides.get((periodId, categoryId)).collect { case (cents, at) if !dayOf(at).isAfter(day) => cents }
  }
}
