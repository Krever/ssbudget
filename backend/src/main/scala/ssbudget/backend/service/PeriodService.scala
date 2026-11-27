package ssbudget.backend.service

import cats.effect.IO
import cats.implicits.*
import ssbudget.backend.db.Repositories
import ssbudget.backend.db.repository.DailyFlow
import ssbudget.shared.api.*
import ssbudget.shared.model.*

import java.time.{Instant, LocalDate, ZoneOffset}
import java.util.UUID

/** The periods: their lifecycle (start, expected end, planned saving) and what the Periods page and the Dashboard report about them. */
class PeriodService(repos: Repositories, clock: AppClock = AppClock.system) {

  import PeriodService.*

  // ---- lifecycle ----

  /** Correct when a period is expected to end. The date is the period's own — nothing re-derives it — so this is the whole of "the paycheck came a
    * few days early", "it came late", or "the pay cycle changed shape".
    */
  def setExpectedEnd(id: PeriodId, expectedEnd: LocalDate): IO[Either[ApiError, Period]] =
    repos.periods.findById(id).flatMap {
      case None           => IO.pure(Left(ApiError.notFound(s"Period not found: ${id.value}")))
      case Some(existing) => repos.periods.updateExpectedEnd(id, expectedEnd).as(Right(existing.copy(expectedEnd = expectedEnd)))
    }

  /** Set how much a period plans to move to savings. A negative amount has no meaning and is refused. */
  def setPlannedSaving(id: PeriodId, plannedSavingCents: Long): IO[Either[ApiError, Period]] =
    if plannedSavingCents < 0 then IO.pure(Left(ApiError.invalid("Planned saving cannot be negative")))
    else {
      repos.periods.findById(id).flatMap {
        case None           => IO.pure(Left(ApiError.notFound(s"Period not found: ${id.value}")))
        case Some(existing) =>
          repos.periods.updatePlannedSaving(id, plannedSavingCents).as(Right(existing.copy(plannedSavingCents = plannedSavingCents)))
      }
    }

  /** Close the open period and open the next one, in one transaction: there is never a moment with no open period, or with two.
    *
    * `asOf` is the day the new period starts. With no date, or with today, it starts now. An earlier day is for a paycheck that came some days before
    * the period was closed: the new period then starts at the beginning of that day, and the days from it on, which were recorded for the old period,
    * are given to the new one. The next pass of [[BudgetSnapshotService]] computes their terms again.
    */
  def startNew(asOf: Option[LocalDate]): IO[Either[ApiError, Period]] =
    for {
      // To the millisecond, as the database stores an instant: the period that this call returns is then equal to the stored one.
      now     <- clock.now.map(_.truncatedTo(java.time.temporal.ChronoUnit.MILLIS))
      current <- repos.periods.findCurrent
      result  <- startInstant(asOf, now, current) match {
                   case Left(reason) => IO.pure(Left(reason))
                   case Right(at)    =>
                     // The plan to save is carried to the new period; it is a habit, not a decision of one month.
                     val next =
                       Period(PeriodId(UUID.randomUUID().toString), at, Period.defaultExpectedEnd(at), None, current.fold(0L)(_.plannedSavingCents))
                     repos.periods.rollover(current.map(_.id), at, next) *>
                       repos.dailyBudgetSnapshots.moveToPeriod(next.startDay, next).as(Right(next))
                 }
    } yield result

  // ---- reports ----

  /** Balance at the current period's start per savings and investment account (see [[AccountPeriodBaseline]] — the client derives the Δ from its live
    * balances). No current period, or no recorded history for an account → no entry, and the client shows no Δ. Informational only — not part of the
    * free-money calc.
    */
  def baselines: IO[List[AccountPeriodBaseline]] =
    for {
      accounts  <- repos.accounts.findAll
      periodOpt <- repos.periods.findCurrent
      baselines <- periodOpt.toList.flatTraverse { p =>
                     val held   = accounts.filter(a => AccountRole.held(a.role))
                     // The balance at the period's start. An account whose first balance was recorded after that start (bank balances are
                     // recorded on sync, so this is usual for a new link) gets it from the ledger: see [[BalanceHistory]].
                     val window = PeriodWindow.of(p)
                     for {
                       history  <- BalanceHistory.load(repos, held, clock)
                       today    <- clock.today
                       rates    <- RateTable.load(repos)
                       cats     <- repos.categories.findAll
                       flows    <- repos.bankTransactions.dailyFlowsSince(window.from)
                       uidIndex <- BalanceHistory.accountUidIndex(repos)
                     } yield {
                       val moved = movedInto(flows.filter(f => window.holds(f.day)), transferAccounts(cats), uidIndex, rates)
                       held.flatMap { acc =>
                         history
                           .asOf(acc.id, p.startDate)
                           .map(b =>
                             AccountPeriodBaseline(
                               acc.id,
                               Money(b, acc.currency),
                               // The rate of now against the rate of the day that the baseline is of. It does not depend on the live balance.
                               currencyEffectCents = HeldChange
                                 .of(b, acc.balanceCents, acc.currency, PeriodWindow.lastLedgerDayBefore(p.startDate), today, rates)
                                 .currencyEffectCents,
                               // "Moved" is a figure of investment accounts only.
                               movedCents = if acc.role == AccountRole.Investment then moved.getOrElse(acc.id, 0L) else 0L,
                             ),
                           )
                       }
                     }
                   }
    } yield baselines

  /** Per-period retrospective, newest first. See [[PeriodSummary]] for what each figure means and where it comes from. */
  def summaries(limitOpt: Option[Int]): IO[List[PeriodSummary]] =
    for {
      all      <- repos.periods.findAll
      periods   = all.sortBy(_.startDate.toEpochMilli).reverse.take(limitOpt.filter(_ > 0).getOrElse(periodSummaryDefaultCount).min(60))
      accounts <- repos.accounts.findAll
      cats     <- repos.categories.findAll
      rates    <- RateTable.load(repos)
      // Every period needs each account's balance at two instants. The history of each account is read ONCE here, and the boundaries are resolved
      // in memory.
      history  <- BalanceHistory.load(repos, accounts, clock)
      now      <- clock.now
      today    <- clock.today
      // The entries of all listed periods are read ONCE too, and each period sums the rows of its own days. With a set of queries for each
      // period, a page of 16 periods ran about 200 statements on the one database connection.
      flows    <- periods.map(PeriodWindow.of(_).from).minOption.fold(IO.pure(List.empty[DailyFlow]))(repos.bankTransactions.dailyFlowsSince)
      uidIndex <- BalanceHistory.accountUidIndex(repos)
    } yield periods.map(periodSummary(rates, accounts, cats, history, now, today, flows, uidIndex))

  private def periodSummary(
      rates: RateTable,
      accounts: List[Account],
      cats: List[Category],
      history: BalanceHistory,
      now: Instant,
      today: LocalDate,
      allFlows: List[DailyFlow],
      uidIndex: Map[String, AccountId],
  )(p: Period): PeriodSummary = {
    val spending   = accounts.filter(_.role == AccountRole.Spending)
    val savings    = accounts.filter(_.role == AccountRole.Savings)
    val investment = accounts.filter(_.role == AccountRole.Investment)
    val holding    = accounts.filter(_.role == AccountRole.Holding)

    // A balance at a period boundary: the recorded balance at or before that instant, and, before the first record of an account, that record
    // carried back through the bank ledger (see [[BalanceHistory]]). So a period from before the balances were recorded shows what the bank
    // ledger says the accounts did, and not zero. A running period has no end: its "balance at the end" is the live balance.
    def balanceAt(acc: Account, at: Option[Instant]): Option[Long] =
      at.fold(Option(acc.balanceCents))(history.asOf(acc.id, _))
    // Each balance is valued at the rate of the day that it is of: the start at the rate of the period's start, the end at the rate of its end
    // (today, for the running period). So the net worth of a closed period stays what it was, and the change of a foreign account holds what
    // the rate did (see [[HeldChange]]).
    val startDay                                                   = PeriodWindow.lastLedgerDayBefore(p.startDate)
    val endDay                                                     = p.endDate.fold(today)(PeriodWindow.lastLedgerDayBefore)
    def endValue(acc: Account): Option[Long]                       = balanceAt(acc, p.endDate).map(rates.convert(_, acc.currency, endDay))
    def changeOf(group: List[Account]): HeldChange                 =
      group
        .flatMap { acc =>
          // None only for an account with no recorded balance at all.
          (balanceAt(acc, Some(p.startDate)), balanceAt(acc, p.endDate))
            .mapN((start, end) => HeldChange.of(start, end, acc.currency, startDay, endDay, rates))
        }
        .foldLeft(HeldChange.zero)(_ + _)
    def endOf(group: List[Account]): Option[Long]                  = {
      val ends = group.flatMap(endValue)
      Option.when(ends.nonEmpty)(ends.sum)
    }
    val savingsChange                                              = changeOf(savings)
    val investmentChange                                           = changeOf(investment)
    val endBalances                                                = spending.map(endValue)
    val holdingEnds                                                = holding.flatMap(endValue)

    // The entries of this period: those of its days (see [[PeriodWindow]]).
    val rows = allFlows.filter(f => PeriodWindow.of(p).holds(f.day))

    // A transfer category stands for the transfers to and from one investment account: neither income nor spend.
    val transferAccount                   = transferAccounts(cats)
    def isTransfer(f: DailyFlow): Boolean = f.category.exists(transferAccount.contains)

    val cash         = rows.filterNot(isTransfer)
    val inflowCents  = inPrimary(cash, rates)(_ => ())(_.inflow).getOrElse((), 0L)
    val outflowCents = inPrimary(cash, rates)(_ => ())(_.outflow).getOrElse((), 0L)

    val moved = movedInto(rows, transferAccount, uidIndex, rates)

    // Spend by category: what went out, refunds not taken off. A deposit to an investment account is not spend, so a transfer category has
    // no place in the breakdown.
    val spentByCategory = inPrimary(rows.filter(f => f.category.isDefined && !isTransfer(f)), rates)(_.category.get)(_.outflow)

    {
      val catIndex      = cats.map(c => c.id -> c).toMap
      val topCategories = spentByCategory.toList
        .flatMap { case (id, cents) => catIndex.get(id).map(PeriodCategorySpend(_, cents)) }
        .filter(_.spentCents > 0)
        .sortBy(-_.spentCents)
        .take(periodSummaryCategoryCap)

      PeriodSummary(
        period = p,
        days = periodDays(p, now),
        currency = rates.primary,
        inflowCents = inflowCents,
        outflowCents = outflowCents,
        savingsChangeCents = savingsChange.flowCents,
        savingsCurrencyEffectCents = savingsChange.currencyEffectCents,
        savingsEndCents = endOf(savings),
        investmentChangeCents = investmentChange.totalCents,
        investmentMovedCents = investment.map(acc => moved.getOrElse(acc.id, 0L)).sum,
        investmentEndCents = endOf(investment),
        // Partial by design: accounts without a snapshot that early are left out rather than sinking the whole figure to None.
        endBalanceCents = Option.when(endBalances.exists(_.isDefined))(endBalances.flatten.sum),
        holdingEndCents = Option.when(holdingEnds.nonEmpty)(holdingEnds.sum),
        topCategories = topCategories,
      )
    }
  }
}

object PeriodService {

  /** The change of the primary-currency value of an account (or of a group) across a period, in two parts:
    *
    * `value at end − value at start = (end − start) × end rate + start × (end rate − start rate)`
    *
    * The first part, [[flowCents]], is what came in and went out, valued at the rate of the end. The second, [[currencyEffectCents]], is what the
    * rate did to the balance that the account had at the start. For an account in the primary currency it is zero. "Saved" counts the flow of the
    * savings accounts; the currency effect is part of the market result.
    */
  final case class HeldChange(flowCents: Long, totalCents: Long) {
    def currencyEffectCents: Long        = totalCents - flowCents
    def +(other: HeldChange): HeldChange = HeldChange(flowCents + other.flowCents, totalCents + other.totalCents)
  }

  object HeldChange {
    val zero: HeldChange = HeldChange(0L, 0L)

    /** `startCents` and `endCents` are in `currency`; `startDay` and `endDay` are the days that the two balances are of. */
    def of(startCents: Long, endCents: Long, currency: Currency, startDay: LocalDate, endDay: LocalDate, rates: RateTable): HeldChange =
      HeldChange(
        flowCents = rates.convert(endCents - startCents, currency, endDay),
        totalCents = rates.convert(endCents, currency, endDay) - rates.convert(startCents, currency, startDay),
      )
  }

  /** transfer category -> the investment account whose deposits and withdrawals it holds. */
  private def transferAccounts(cats: List[Category]): Map[CategoryId, AccountId] = cats.flatMap(c => c.transferAccount.map(c.id -> _)).toMap

  /** Sums of `rows` in the primary currency, by `key`. Each amount is converted at the rate of its own day. */
  private def inPrimary[K](rows: List[DailyFlow], rates: RateTable)(key: DailyFlow => K)(amount: DailyFlow => Long): Map[K, Long] =
    rows.groupMapReduce(key)(f => rates.convert(amount(f), f.currency, f.day))(_ + _)

  /** The net amount moved INTO each investment account by the entries `rows`, in the primary currency. Negative = taken out. It has two sources:
    *   - the transfer categories of the account: the opposite of what the bank side shows (a withdrawal arrives at the bank). An entry on the
    *     account's own ledger is not counted here: it is a movement of that account, and the second source has it;
    *   - the account's own ledger, when it has one (for example card payments imported from a statement of the broker): its non-internal net, with
    *     its own sign. A payment from the account is money taken out of the investment, and it is spend as on any other account.
    *
    * The map has an entry for other accounts with a ledger too. Callers read only the investment accounts.
    */
  private def movedInto(
      rows: List[DailyFlow],
      transferAccount: Map[CategoryId, AccountId],
      uidIndex: Map[String, AccountId],
      rates: RateTable,
  ): Map[AccountId, Long] = {
    val target        = (f: DailyFlow) => f.category.flatMap(transferAccount.get)
    val viaCategories = inPrimary(rows.filter(f => target(f).exists(acc => !uidIndex.get(f.accountUid).contains(acc))), rates)(target(_).get)(-_.net)
    val viaLedger     = inPrimary(rows.filter(f => uidIndex.contains(f.accountUid)), rates)(f => uidIndex(f.accountUid))(_.net)
    (viaCategories.toList ++ viaLedger.toList).groupMapReduce(_._1)(_._2)(_ + _)
  }

  /** How many periods the retrospective summarizes when the client doesn't ask for a number. Each period costs a handful of aggregate queries, so
    * this is capped rather than unbounded.
    */
  private val periodSummaryDefaultCount = 24

  /** How many categories each period's spend breakdown lists. */
  private val periodSummaryCategoryCap = 6

  /** A period's length in whole days: its full span once closed, elapsed so far while it's running. */
  def periodDays(p: Period, now: Instant): Int =
    java.time.temporal.ChronoUnit.DAYS.between(p.startDate, p.endDate.getOrElse(now)).toInt

  /** The instant at which a new period starts, or why it cannot start then.
    *
    * No date, or today: now. An earlier day: the beginning of that day (UTC), because a bank entry has a date and no time, and the entries of the day
    * a period opens are of the new period. The day must be after the day the open period started (a period has one day at least), and it cannot be in
    * the future.
    */
  def startInstant(asOf: Option[LocalDate], now: Instant, current: Option[Period]): Either[ApiError, Instant] = {
    val today = LocalDate.ofInstant(now, ZoneOffset.UTC)
    asOf.filter(_ != today) match {
      case None                            => Right(now)
      case Some(day) if day.isAfter(today) => Left(ApiError.invalid("A period cannot start in the future"))
      case Some(day)                       =>
        current match {
          case Some(open) if !day.isAfter(open.startDay) =>
            Left(ApiError.invalid(s"The new period must start after ${open.startDay}, the day the open period started"))
          case _                                         => Right(day.atStartOfDay(ZoneOffset.UTC).toInstant)
        }
    }
  }
}
