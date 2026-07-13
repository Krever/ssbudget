package ssbudget.backend.db.repository

import cats.effect.IO
import cats.implicits.*
import doobie.*
import doobie.implicits.*
import ssbudget.backend.db.DoobieMeta.given
import ssbudget.shared.model.*

import java.time.Instant

trait BankTransactionRepository {

  /** Insert a transaction, or if one with the same (ebAccountUid, dedupKey) already exists, refresh its bank-derived fields from the latest parse (so
    * parser improvements propagate on re-import) while preserving its id, manual `category_id`, and `is_internal`. Returns true iff newly inserted.
    */
  def insertNew(tx: BankTransaction): IO[Boolean]
  def findById(id: BankTransactionId): IO[Option[BankTransaction]]
  def list(accountUid: Option[String], from: Option[Instant], to: Option[Instant]): IO[List[BankTransaction]]

  /** Server-side filtered/sorted/capped view for the Transactions page (the browser can't hold thousands of rows).
    *
    *   - `month` filters on the YYYY-MM of `booked_at`.
    *   - `from`/`to` bound `booked_at` (`>= from`, `< to`) — used for the "current period" filter; combine freely with the other filters.
    *   - `category`: `None`/"all" = no filter; "uncategorized" = no category AND not internal (triage view); otherwise a specific category id.
    *   - `hideInternal` drops own-account transfers.
    *   - `sort` is "amount" (signed cents) or "date" (booked_at); `asc` flips the direction (default newest/largest first).
    *   - `limit` caps the returned rows; `total` is the full match count before the cap.
    *
    * Returns `(rows, total, sums)` where `sums` is the net signed `amount_cents` per currency over the FULL match (ignoring the cap), so the UI can
    * show a reliable total even when rows are truncated.
    */
  def query(
      accountUid: Option[String],
      month: Option[String],
      from: Option[Instant],
      to: Option[Instant],
      category: Option[String],
      hideInternal: Boolean,
      sort: String,
      asc: Boolean,
      limit: Option[Int],
  ): IO[(List[BankTransaction], Int, List[(Currency, Long)])]

  /** Distinct YYYY-MM buckets present in the data, newest first — for the month filter dropdown. */
  def distinctMonths(): IO[List[String]]

  /** Spend (positive cents) per (category, currency) over `[from, to)`, categorized + non-internal. `includeInflows=false` counts only debits (gross
    * outflow); `true` sums the NET (`SUM(-amount_cents)`, so inflows/refunds subtract) — the latter powers category budgets so pure-inflow categories
    * (salary, refunds) aren't reported as 0 and refunds reduce a category's spend.
    */
  def spendByCategoryBetween(from: Instant, to: Option[Instant], includeInflows: Boolean = false): IO[List[(CategoryId, Currency, Long)]]

  /** Spend per (category, currency, YYYY-MM) over `[from, to)`, categorized + non-internal — a per-month breakdown. `includeInflows` as in
    * [[spendByCategoryBetween]] (false = gross outflow, e.g. for the analytics chart; true = net, for the budget average).
    */
  def monthlySpendByCategory(from: Instant, to: Instant, includeInflows: Boolean = false): IO[List[(CategoryId, Currency, String, Long)]]

  /** Import/categorization health counts across all stored transactions, as a tuple of (total, internal, categorized, uncategorized, manual, rule)
    * where categorized/uncategorized count only non-internal rows and manual/rule reflect `category_source`.
    */
  def categorizationCounts(): IO[(Int, Int, Int, Int, Int, Int)]

  /** Outflow (positive cents) per currency for non-internal, uncategorized debits — the still-to-triage backlog, before currency conversion. */
  def uncategorizedOutflowByCurrency(): IO[List[(Currency, Long)]]

  /** Counterparties with the most uncategorized outflow (non-internal debits without a category), per (counterparty, currency): (name, currency,
    * count, outflow cents). The actionable list for creating new rules. `limit` caps the (name, currency) rows scanned.
    */
  def topUncategorizedCounterparties(limit: Int): IO[List[(Option[String], Currency, Int, Long)]]

  /** Most recent booked_at for an account, for incremental imports (None when the account has no transactions yet). */
  def latestBookedAt(ebAccountUid: String): IO[Option[Instant]]

  /** Assign/clear a category as a manual action: stamps `category_source = 'manual'` (or NULL when clearing) so the rule engine never overwrites it.
    */
  def setCategory(id: BankTransactionId, categoryId: Option[CategoryId]): IO[Unit]
  def clearCategory(categoryId: CategoryId): IO[Unit]

  /** Set (or clear, when None) a transaction's free-text note. */
  def setNote(id: BankTransactionId, note: Option[String]): IO[Unit]
  def deleteByConnection(connectionId: BankConnectionId): IO[Unit]

  /** Apply the rule engine's decisions in one transaction: each tuple sets a row's (category_id, category_source). Only changed rows should be
    * passed.
    */
  def applyCategoryUpdates(updates: List[(BankTransactionId, Option[CategoryId], Option[CategorySource])]): IO[Unit]

  /** Built-in rule: flag a transaction as an internal transfer when its counterparty IBAN is one of the user's own linked accounts, OR its remittance
    * matches a known own-movement descriptor (card repayment / auto-save, which carry no counterparty IBAN). Recomputes all rows; idempotent.
    */
  def markInternalTransfers(): IO[Unit]
}

class BankTransactionRepositoryImpl(xa: Transactor[IO]) extends BankTransactionRepository {

  private val columns =
    fr"id, connection_id, eb_account_uid, entry_reference, dedup_key, amount_cents, currency, status, booked_at, counterparty_name, counterparty_account, remittance, bank_transaction_code, category_id, raw_json, imported_at, is_internal, category_source, note"

  override def insertNew(tx: BankTransaction): IO[Boolean] = {
    val program = for {
      existed <- sql"SELECT EXISTS(SELECT 1 FROM bank_transactions WHERE eb_account_uid = ${tx.ebAccountUid} AND dedup_key = ${tx.dedupKey})"
                   .query[Boolean]
                   .unique
      _       <- sql"""
        INSERT INTO bank_transactions
          (id, connection_id, eb_account_uid, entry_reference, dedup_key, amount_cents, currency, status, booked_at,
           counterparty_name, counterparty_account, remittance, bank_transaction_code, category_id, raw_json, imported_at, is_internal, category_source, note)
        VALUES (${tx.id}, ${tx.connectionId}, ${tx.ebAccountUid}, ${tx.entryReference}, ${tx.dedupKey}, ${tx.amountCents}, ${tx.currency},
                ${tx.status}, ${tx.bookedAt}, ${tx.counterpartyName}, ${tx.counterpartyAccount}, ${tx.remittance},
                ${tx.bankTransactionCode}, ${tx.categoryId}, ${tx.rawJson}, ${tx.importedAt}, ${tx.internal}, ${tx.categorySource}, ${tx.note})
        ON CONFLICT(eb_account_uid, dedup_key) DO UPDATE SET
          connection_id = excluded.connection_id, entry_reference = excluded.entry_reference, amount_cents = excluded.amount_cents,
          currency = excluded.currency, status = excluded.status, booked_at = excluded.booked_at,
          counterparty_name = excluded.counterparty_name, counterparty_account = excluded.counterparty_account,
          remittance = excluded.remittance, bank_transaction_code = excluded.bank_transaction_code, raw_json = excluded.raw_json
      """.update.run
    } yield !existed
    program.transact(xa)
  }

  override def findById(id: BankTransactionId): IO[Option[BankTransaction]] =
    (fr"SELECT" ++ columns ++ fr"FROM bank_transactions WHERE id = $id").query[BankTransaction].option.transact(xa)

  override def list(accountUid: Option[String], from: Option[Instant], to: Option[Instant]): IO[List[BankTransaction]] = {
    val conds = List(
      accountUid.map(u => fr"eb_account_uid = $u"),
      from.map(f => fr"booked_at >= $f"),
      to.map(t => fr"booked_at <= $t"),
    ).flatten
    val where = if conds.isEmpty then Fragment.empty else fr"WHERE" ++ conds.reduce(_ ++ fr"AND" ++ _)
    (fr"SELECT" ++ columns ++ fr"FROM bank_transactions" ++ where ++ fr"ORDER BY booked_at DESC").query[BankTransaction].to[List].transact(xa)
  }

  override def query(
      accountUid: Option[String],
      month: Option[String],
      from: Option[Instant],
      to: Option[Instant],
      category: Option[String],
      hideInternal: Boolean,
      sort: String,
      asc: Boolean,
      limit: Option[Int],
  ): IO[(List[BankTransaction], Int, List[(Currency, Long)])] = {
    val conds    = List(
      accountUid.map(u => fr"eb_account_uid = $u"),
      month.map(m => fr"substr(booked_at, 1, 7) = $m"),
      from.map(f => fr"booked_at >= $f"),
      to.map(t => fr"booked_at < $t"),
      category.filterNot(c => c == "all").map {
        case "uncategorized" => fr"category_id IS NULL AND is_internal = 0"
        case cid             => fr"category_id = ${CategoryId(cid)}"
      },
      Option.when(hideInternal)(fr"is_internal = 0"),
    ).flatten
    val where    = if conds.isEmpty then Fragment.empty else fr"WHERE" ++ conds.reduce(_ ++ fr"AND" ++ _)
    val orderCol = if sort == "amount" then fr"ORDER BY amount_cents" else fr"ORDER BY booked_at"
    val orderDir = if asc then fr"ASC" else fr"DESC"
    val limitFr  = limit.fold(Fragment.empty)(n => fr"LIMIT $n")
    val itemsQ   = (fr"SELECT" ++ columns ++ fr"FROM bank_transactions" ++ where ++ orderCol ++ orderDir ++ limitFr).query[BankTransaction].to[List]
    val countQ   = (fr"SELECT COUNT(*) FROM bank_transactions" ++ where).query[Int].unique
    // Net signed sum per currency over the FULL match (independent of the display cap) — powers the table's total row.
    val sumsQ    = (fr"SELECT currency, SUM(amount_cents) FROM bank_transactions" ++ where ++ fr"GROUP BY currency").query[(Currency, Long)].to[List]
    (itemsQ, countQ, sumsQ).tupled.transact(xa)
  }

  override def distinctMonths(): IO[List[String]] =
    sql"SELECT DISTINCT substr(booked_at, 1, 7) AS m FROM bank_transactions ORDER BY m DESC".query[String].to[List].transact(xa)

  override def spendByCategoryBetween(from: Instant, to: Option[Instant], includeInflows: Boolean): IO[List[(CategoryId, Currency, Long)]] = {
    val upper = to.fold(Fragment.empty)(t => fr"AND booked_at <" ++ fr"$t")
    val debit = if includeInflows then Fragment.empty else fr"AND amount_cents < 0"
    (fr"""SELECT category_id, currency, SUM(-amount_cents)
          FROM bank_transactions
          WHERE category_id IS NOT NULL AND is_internal = 0""" ++ debit ++ fr"AND booked_at >= $from" ++ upper ++
      fr"GROUP BY category_id, currency").query[(CategoryId, Currency, Long)].to[List].transact(xa)
  }

  override def monthlySpendByCategory(from: Instant, to: Instant, includeInflows: Boolean): IO[List[(CategoryId, Currency, String, Long)]] = {
    val debit = if includeInflows then Fragment.empty else fr"AND amount_cents < 0"
    (fr"""SELECT category_id, currency, substr(booked_at, 1, 7) AS ym, SUM(-amount_cents)
          FROM bank_transactions
          WHERE category_id IS NOT NULL AND is_internal = 0""" ++ debit ++ fr"AND booked_at >= $from AND booked_at < $to" ++
      fr"GROUP BY category_id, currency, ym").query[(CategoryId, Currency, String, Long)].to[List].transact(xa)
  }

  override def categorizationCounts(): IO[(Int, Int, Int, Int, Int, Int)] =
    sql"""SELECT
            COUNT(*),
            COALESCE(SUM(CASE WHEN is_internal = 1 THEN 1 ELSE 0 END), 0),
            COALESCE(SUM(CASE WHEN is_internal = 0 AND category_id IS NOT NULL THEN 1 ELSE 0 END), 0),
            COALESCE(SUM(CASE WHEN is_internal = 0 AND category_id IS NULL THEN 1 ELSE 0 END), 0),
            COALESCE(SUM(CASE WHEN category_source = 'manual' THEN 1 ELSE 0 END), 0),
            COALESCE(SUM(CASE WHEN category_source = 'rule' THEN 1 ELSE 0 END), 0)
          FROM bank_transactions""".query[(Int, Int, Int, Int, Int, Int)].unique.transact(xa)

  override def uncategorizedOutflowByCurrency(): IO[List[(Currency, Long)]] =
    sql"""SELECT currency, SUM(-amount_cents)
          FROM bank_transactions
          WHERE is_internal = 0 AND category_id IS NULL AND amount_cents < 0
          GROUP BY currency""".query[(Currency, Long)].to[List].transact(xa)

  override def topUncategorizedCounterparties(limit: Int): IO[List[(Option[String], Currency, Int, Long)]] =
    sql"""SELECT counterparty_name, currency, COUNT(*), SUM(-amount_cents)
          FROM bank_transactions
          WHERE is_internal = 0 AND category_id IS NULL AND amount_cents < 0
          GROUP BY counterparty_name, currency
          ORDER BY SUM(-amount_cents) DESC
          LIMIT $limit""".query[(Option[String], Currency, Int, Long)].to[List].transact(xa)

  override def latestBookedAt(ebAccountUid: String): IO[Option[Instant]] =
    sql"SELECT MAX(booked_at) FROM bank_transactions WHERE eb_account_uid = $ebAccountUid".query[Option[Instant]].unique.transact(xa)

  override def setCategory(id: BankTransactionId, categoryId: Option[CategoryId]): IO[Unit] = {
    val source: Option[CategorySource] = categoryId.map(_ => CategorySource.Manual)
    sql"UPDATE bank_transactions SET category_id = $categoryId, category_source = $source WHERE id = $id".update.run.transact(xa).void
  }

  override def clearCategory(categoryId: CategoryId): IO[Unit] =
    sql"UPDATE bank_transactions SET category_id = NULL, category_source = NULL WHERE category_id = $categoryId".update.run.transact(xa).void

  override def setNote(id: BankTransactionId, note: Option[String]): IO[Unit] =
    sql"UPDATE bank_transactions SET note = $note WHERE id = $id".update.run.transact(xa).void

  override def applyCategoryUpdates(updates: List[(BankTransactionId, Option[CategoryId], Option[CategorySource])]): IO[Unit] =
    if updates.isEmpty then IO.unit
    else {
      val sql  = "UPDATE bank_transactions SET category_id = ?, category_source = ? WHERE id = ?"
      val rows = updates.map { case (id, cat, src) => (cat, src, id) }
      Update[(Option[CategoryId], Option[CategorySource], BankTransactionId)](sql).updateMany(rows).transact(xa).void
    }

  override def deleteByConnection(connectionId: BankConnectionId): IO[Unit] =
    sql"DELETE FROM bank_transactions WHERE connection_id = $connectionId".update.run.transact(xa).void

  override def markInternalTransfers(): IO[Unit] = {
    // A transaction is internal when EITHER its counterparty is one of our own accounts (by IBAN), OR its remittance is one of the bank's own-movement
    // descriptors that carry no counterparty IBAN (credit-card repayments landing on the card, auto-savings sweeps).
    val ownIbanMatch    =
      fr"""(counterparty_account IS NOT NULL AND REPLACE(UPPER(counterparty_account), ' ', '') IN
            (SELECT REPLACE(UPPER(iban), ' ', '') FROM bank_account_links WHERE iban IS NOT NULL))"""
    val descriptorMatch = BankTransactionRepositoryImpl.internalRemittancePatterns.map(p => fr"remittance LIKE ${s"%$p%"}")
    val isInternal      = (ownIbanMatch :: descriptorMatch).reduce(_ ++ fr" OR " ++ _)
    (fr"UPDATE bank_transactions SET is_internal = CASE WHEN (" ++ isInternal ++ fr") THEN 1 ELSE 0 END").update.run.transact(xa).void
  }
}

object BankTransactionRepositoryImpl {

  /** Bank descriptors that mark an internal money movement even when no counterparty IBAN is reported (PKO). Stored remittances are upper-case; LIKE
    * is case-insensitive for the ASCII parts and matches the Polish letters as-is. Extend as new patterns surface.
    */
  val internalRemittancePatterns: List[String] = List(
    "SPŁATA NALEŻNOŚCI", // credit-card repayment received (lands on the card account)
    "AUTOOSZCZĘDZANIE",  // automatic savings sweep
    "AUTOSAVER",
  )
}
