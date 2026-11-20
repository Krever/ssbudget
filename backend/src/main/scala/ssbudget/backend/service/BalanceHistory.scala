package ssbudget.backend.service

import cats.effect.IO
import cats.syntax.all.*
import ssbudget.backend.db.Repositories
import ssbudget.shared.model.*

import java.time.{Instant, LocalDate, ZoneOffset}

/** The balance of an account at a past instant, also for the time before its first recorded balance.
  *
  * A balance is recorded on each sync and on each manual edit, so a record is some days old when nobody opened the app. The answer is thus the last
  * record at or before the instant, carried FORWARD through the transaction ledger: plus all that the account moved on the days after the record's
  * day and before the instant's day. A record of the same day or of the day before is the answer as it is.
  *
  * Before the first record there is nothing measured, and the ledger gives the answer the other way: the first recorded balance, less all that the
  * account moved between the instant and that record ([[BalanceHistory.carriedBack]]). On the recorded periods this gave the bank's own running
  * balance to the cent.
  *
  * A bank entry has a date and no time. The entries of the instant's own day count as AFTER the instant: the answer from the ledger is the balance at
  * the end of the day before. These instants are period boundaries, and a period opens when the paycheck arrives; what moves on that day (the rest of
  * the paycheck, a transfer to savings) is thus of the new period. Where a balance was measured at the moment a period opened, the transfers of that
  * day did come after it.
  *
  * An account with no transactions (a manual one) has no ledger, so its first recorded balance stands for all the time before it.
  */
final case class BalanceHistory(
    snapshots: Map[AccountId, List[BalanceSnapshot]], // oldest first
    ledger: Map[AccountId, Map[LocalDate, Long]],     // day -> signed movement, in the account's currency
) {

  /** The balance at `at`, in the account's currency. None for an account with no recorded balance at all. */
  def asOf(account: AccountId, at: Instant): Option[Long] = {
    val history = snapshots.getOrElse(account, Nil)
    val moves   = ledger.getOrElse(account, Map.empty)
    val lastDay = PeriodWindow.lastLedgerDayBefore(at)
    history
      .findLast(!_.recordedAt.isAfter(at))
      .map(record => BalanceHistory.carriedForward(record, moves, lastDay))
      .orElse(BalanceHistory.carriedBack(history, moves, lastDay))
  }
}

object BalanceHistory {

  /** All recorded balances and the full ledger of `accounts`. */
  def load(repos: Repositories, accounts: List[Account], clock: AppClock = AppClock.system): IO[BalanceHistory] =
    for {
      snaps    <- accounts.traverse(a => repos.balanceSnapshots.findByAccount(a.id).map(a.id -> _)).map(_.toMap)
      now      <- clock.now
      daily    <- repos.bankTransactions.dailyNetByAccount(Instant.EPOCH, now)
      uidIndex <- accountUidIndex(repos)
    } yield BalanceHistory(snaps.view.mapValues(_.sortBy(_.recordedAt.toEpochMilli)).toMap, ledgerByAccount(daily, uidIndex))

  /** The balance at the end of `day`, from a record of that day or of an earlier one: the record, plus what the account moved on the days after the
    * record's day, to `day`. The record's own day counts as already in the record (a balance is read in the middle of a day, and a bank entry has a
    * date and no time), which is the rule of [[BudgetSnapshotService]] for a past day too.
    */
  def carriedForward(record: BalanceSnapshot, ledger: Map[LocalDate, Long], day: LocalDate): Long = {
    val recordDay = dayOf(record.recordedAt)
    record.amount + ledger.collect { case (d, cents) if d.isAfter(recordDay) && !d.isAfter(day) => cents }.sum
  }

  /** The balance at the end of `day`, for a `day` before the first record in `history` (oldest first).
    *
    * The anchor is the LAST record of the first recorded day, not the first record. An account that is made in the app starts with a record of zero,
    * and the real balance comes with the sync a moment later; the first record would thus anchor all earlier days to zero. Bank entries carry a date
    * and no time, so the anchor's own day counts as already in the anchor, and `day`'s own entries count as before the end of `day`.
    */
  def carriedBack(history: List[BalanceSnapshot], ledger: Map[LocalDate, Long], day: LocalDate): Option[Long] =
    history.headOption.map { first =>
      val anchorDay = dayOf(first.recordedAt)
      val anchor    = history.takeWhile(s => dayOf(s.recordedAt) == anchorDay).last
      anchor.amount - ledger.collect { case (d, cents) if d.isAfter(day) && !d.isAfter(anchorDay) => cents }.sum
    }

  /** The rows of `dailyNetByAccount`, keyed by the app account that each bank account moves. The cards of a card group are several bank accounts that
    * feed one app account, so their movements add up. The amounts stay in the account's currency.
    */
  def ledgerByAccount(daily: List[(String, Currency, String, Long)], uidIndex: Map[String, AccountId]): Map[AccountId, Map[LocalDate, Long]] =
    daily
      .flatMap { case (uid, _, d, c) => uidIndex.get(uid).map((_, LocalDate.parse(d), c)) }
      .groupBy(_._1)
      .view
      .mapValues(_.groupMapReduce(_._2)(_._3)(_ + _))
      .toMap

  /** bank account uid -> the app account whose balance it moves, resolving a card-group member to the group's account. */
  def accountUidIndex(repos: Repositories): IO[Map[String, AccountId]] =
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

  private def dayOf(instant: Instant): LocalDate = LocalDate.ofInstant(instant, ZoneOffset.UTC)
}
