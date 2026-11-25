package ssbudget.backend.service

import cats.effect.IO
import cats.implicits.*
import ssbudget.backend.db.Repositories
import ssbudget.backend.db.repository.DailyFlow
import ssbudget.backend.service.BudgetQueries.*
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
                     val savings = accounts.filter(a => AccountRole.held(a.role))
                     // The balance at the period's start. An account whose first balance was recorded after that start (bank balances are
                     // recorded on sync, so this is usual for a new link) gets it from the ledger: see [[BalanceHistory]].
                     val window  = PeriodWindow.of(p)
                     for {
                       history   <- BalanceHistory.load(repos, savings, clock)
                       converter <- primaryConverter(repos)
                       moved     <- movedToInvestments(converter._2)(window.from, window.until)
                     } yield savings.flatMap { acc =>
                       history
                         .asOf(acc.id, p.startDate)
                         .map(b =>
                           AccountPeriodBaseline(
                             acc.id,
                             Money(b, acc.currency),
                             // "Moved" is a figure of investment accounts only.
                             if acc.role == AccountRole.Investment then moved.getOrElse(acc.id, 0L) else 0L,
                           ),
                         )
                     }
                   }
    } yield baselines

  /** The net amount moved INTO each investment account over `[from, to)`, in the primary currency. Negative = taken out. It has two sources:
    *   - the transfer categories of the account: the opposite of what the bank side shows (a withdrawal arrives at the bank);
    *   - the account's own ledger, when it has one (for example card payments imported from a statement of the broker): its non-internal net, with
    *     its own sign. A payment from the account is money taken out of the investment, and it is spend as on any other account.
    *
    * The map has an entry for other accounts with a ledger too. Callers read only the investment accounts.
    */
  private def movedToInvestments(toPrimary: (Long, Currency) => Long)(
      from: Instant,
      to: Option[Instant],
  ): IO[Map[AccountId, Long]] =
    for {
      transfers <- repos.bankTransactions.transfersByAccountBetween(from, to)
      ownLedger <- repos.bankTransactions.netByAccountBetween(from, to)
      uidIndex  <- BalanceHistory.accountUidIndex(repos)
    } yield {
      val viaCategories = transfers.map { case (account, currency, cents) => account -> -toPrimary(cents, currency) }
      val viaLedger     = ownLedger.flatMap { case (uid, currency, cents) => uidIndex.get(uid).map(_ -> toPrimary(cents, currency)) }
      (viaCategories ++ viaLedger).groupMapReduce(_._1)(_._2)(_ + _)
    }

  /** Per-period retrospective, newest first. See [[PeriodSummary]] for what each figure means and where it comes from. */
  def summaries(limitOpt: Option[Int]): IO[List[PeriodSummary]] =
    for {
      all       <- repos.periods.findAll
      periods    = all.sortBy(_.startDate.toEpochMilli).reverse.take(limitOpt.filter(_ > 0).getOrElse(periodSummaryDefaultCount).min(60))
      accounts  <- repos.accounts.findAll
      cats      <- repos.categories.findAll
      converter <- primaryConverter(repos)
      // Every period needs each account's balance at two instants. The history of each account is read ONCE here, and the boundaries are resolved
      // in memory.
      history   <- BalanceHistory.load(repos, accounts, clock)
      now       <- clock.now
      // The entries of all listed periods are read ONCE too, and each period sums the rows of its own days. With a set of queries for each
      // period, a page of 16 periods ran about 200 statements on the one database connection.
      flows     <- periods.map(PeriodWindow.of(_).from).minOption.fold(IO.pure(List.empty[DailyFlow]))(repos.bankTransactions.dailyFlowsSince)
      uidIndex  <- BalanceHistory.accountUidIndex(repos)
    } yield periods.map(periodSummary(converter, accounts, cats, history, now, flows, uidIndex))

  private def periodSummary(
      converter: (Currency, (Long, Currency) => Long),
      accounts: List[Account],
      cats: List[Category],
      history: BalanceHistory,
      now: Instant,
      allFlows: List[DailyFlow],
      uidIndex: Map[String, AccountId],
  )(p: Period): PeriodSummary = {
    val (primary, toPrimary) = converter

    val spending   = accounts.filter(_.role == AccountRole.Spending)
    val savings    = accounts.filter(_.role == AccountRole.Savings)
    val investment = accounts.filter(_.role == AccountRole.Investment)
    val holding    = accounts.filter(_.role == AccountRole.Holding)

    // A balance at a period boundary: the recorded balance at or before that instant, and, before the first record of an account, that record
    // carried back through the bank ledger (see [[BalanceHistory]]). So a period from before the balances were recorded shows what the bank
    // ledger says the accounts did, and not zero. A running period has no end: its "balance at the end" is the live balance.
    def balanceAt(acc: Account, at: Option[Instant]): Option[Long] =
      at.fold(Option(acc.balanceCents))(history.asOf(acc.id, _))
    // The change across the period and the balance at its end, for the accounts of one role. At today's rates, as every other figure of the
    // summary. Thus the change of a foreign account is only what moved, not what the rate did.
    def changeAndEnd(group: List[Account]): (Long, Option[Long])   = {
      val change = group.map { acc =>
        // None only for an account with no recorded balance at all.
        (balanceAt(acc, Some(p.startDate)), balanceAt(acc, p.endDate)).mapN((start, end) => toPrimary(end - start, acc.currency)).getOrElse(0L)
      }.sum
      val ends   = group.flatMap(acc => balanceAt(acc, p.endDate).map(toPrimary(_, acc.currency)))
      (change, Option.when(ends.nonEmpty)(ends.sum))
    }
    val (savingsDelta, savingsEnd)                                 = changeAndEnd(savings)
    val (investmentDelta, investmentEnd)                           = changeAndEnd(investment)
    val endBalances                                                = spending.map(acc => balanceAt(acc, p.endDate).map(toPrimary(_, acc.currency)))
    val holdingEnds                                                = holding.flatMap(acc => balanceAt(acc, p.endDate).map(toPrimary(_, acc.currency)))

    // The entries of this period: those of its days (see [[PeriodWindow]]).
    val rows = allFlows.filter(f => PeriodWindow.of(p).holds(f.day))

    // A transfer category stands for the transfers to and from one investment account: neither income nor spend.
    val transferAccount                                                                                 = cats.flatMap(c => c.transferAccount.map(c.id -> _)).toMap
    def isTransfer(f: DailyFlow): Boolean                                                               = f.category.exists(transferAccount.contains)
    // Each sum is made in the currency of the entries first and converted one time, as a query with GROUP BY currency does.
    def inPrimary[K](of: List[DailyFlow])(key: DailyFlow => K)(amount: DailyFlow => Long): Map[K, Long] =
      of.groupMapReduce(f => (key(f), f.currency))(amount)(_ + _)
        .toList
        .groupMapReduce(_._1._1) { case ((_, currency), cents) =>
          toPrimary(cents, currency)
        }(_ + _)

    val cash         = rows.filterNot(isTransfer)
    val inflowCents  = inPrimary(cash)(_ => ())(_.inflow).getOrElse((), 0L)
    val outflowCents = inPrimary(cash)(_ => ())(_.outflow).getOrElse((), 0L)

    // What was moved INTO each investment account (see [[movedToInvestments]], which answers the same for one window with queries): the
    // opposite of what the bank side shows in its transfer categories, plus the net of its own ledger.
    val viaCategories = inPrimary(
      rows.filter(f => isTransfer(f) && !f.category.flatMap(transferAccount.get).exists(uidIndex.get(f.accountUid).contains)),
    )(f => transferAccount(f.category.get))(_.net).view.mapValues(-_).toMap
    val viaLedger     = inPrimary(rows.filter(f => uidIndex.contains(f.accountUid)))(_.accountUid)(_.net).toList.groupMapReduce { case (uid, _) =>
      uidIndex(uid)
    }(_._2)(_ + _)
    val moved         = (viaCategories.toList ++ viaLedger.toList).groupMapReduce(_._1)(_._2)(_ + _)

    // Spend by category: what went out, refunds not taken off. A deposit to an investment account is not spend, so a transfer category has
    // no place in the breakdown.
    val spentByCategory = inPrimary(rows.filter(f => f.category.isDefined && !isTransfer(f)))(_.category.get)(_.outflow)

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
        currency = primary,
        inflowCents = inflowCents,
        outflowCents = outflowCents,
        savingsChangeCents = savingsDelta,
        savingsEndCents = savingsEnd,
        investmentChangeCents = investmentDelta,
        investmentMovedCents = investment.map(acc => moved.getOrElse(acc.id, 0L)).sum,
        investmentEndCents = investmentEnd,
        // Partial by design: accounts without a snapshot that early are left out rather than sinking the whole figure to None.
        endBalanceCents = Option.when(endBalances.exists(_.isDefined))(endBalances.flatten.sum),
        holdingEndCents = Option.when(holdingEnds.nonEmpty)(holdingEnds.sum),
        topCategories = topCategories,
      )
    }
  }
}

object PeriodService {

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
