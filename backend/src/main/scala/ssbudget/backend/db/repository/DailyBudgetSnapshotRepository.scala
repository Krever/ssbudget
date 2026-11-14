package ssbudget.backend.db.repository

import cats.effect.IO
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import ssbudget.backend.db.DoobieMeta.given
import ssbudget.shared.model.*

import java.time.LocalDate

/** The daily free-money components — one row per day, append-mostly.
  *
  * Writes go through [[recordAll]], which encodes the one precedence rule: a measurement is never replaced by an inference. Today's row is rewritten
  * as the day moves; an earlier day, once filled in, stays as it is.
  *
  * The one exception is [[rederive]]: the terms in [[DerivedTerms]] are not measurements, and the days of the open period follow the record and the
  * configuration as they are now.
  */
trait DailyBudgetSnapshotRepository {

  /** Write a day's components. A `Live` row overwrites whatever was there; a `Reconstructed` one is only written if the day has no row yet. */
  def record(snapshot: DailyBudgetSnapshot): IO[Unit]

  /** [[record]] for many days at once, in one transaction per precedence class.
    *
    * A first pass reconstructs every day since the budget began, and SQLite takes a single writer at a time on a pool that is one connection wide —
    * so a row-at-a-time loop would be a few hundred lock-and-commit cycles that every concurrent request has to queue behind.
    */
  def recordAll(snapshots: List[DailyBudgetSnapshot]): IO[Unit]

  /** The days that already have a row, within `from`..`to` inclusive — what [[ssbudget.backend.service.BudgetSnapshotService]] fills the gaps around.
    */
  def datesBetween(from: LocalDate, to: LocalDate): IO[Set[LocalDate]]

  def findByDate(date: LocalDate): IO[Option[DailyBudgetSnapshot]]

  /** Every row, oldest first. */
  def findAll: IO[List[DailyBudgetSnapshot]]

  /** The days whose row was written before the outside-money terms existed, and so has none. */
  def datesWithoutOutsideTerms: IO[Set[LocalDate]]

  /** Give such rows their terms: `(day, ledger, from savings, unplanned income)`. A row that already has them is left as it is, and so is everything
    * else in the row — a measured row stays measured.
    */
  def fillOutsideTerms(terms: List[(LocalDate, Long, Long, Long)]): IO[Unit]

  /** Replace the derived terms of existing rows. The balances and the source of a row are not touched, so a measured row keeps what was measured. A
    * row whose terms are already equal is not written.
    */
  def rederive(terms: List[DerivedTerms]): IO[Unit]
}

class DailyBudgetSnapshotRepositoryImpl(xa: Transactor[IO]) extends DailyBudgetSnapshotRepository {

  import DailyBudgetSnapshotRepositoryImpl.*

  override def record(snapshot: DailyBudgetSnapshot): IO[Unit] = recordAll(List(snapshot))

  override def recordAll(snapshots: List[DailyBudgetSnapshot]): IO[Unit] = {
    // REPLACE vs IGNORE is the whole precedence rule: a live reading is the better one for a day still in progress, while a reconstruction must never
    // overwrite something that was actually measured at the time. `on_date` is the primary key and nothing references this table, so REPLACE is a
    // plain overwrite.
    val (live, reconstructed) = snapshots.partition(_.source == SnapshotSource.Live)
    val writes                = List("OR IGNORE" -> reconstructed, "OR REPLACE" -> live).collect {
      case (verb, rows) if rows.nonEmpty =>
        Update[DailyBudgetSnapshot](s"INSERT $verb INTO daily_budget_snapshots ($ColumnList) VALUES ($Placeholders)").updateMany(rows)
    }
    writes.sequence_.transact(xa)
  }

  override def datesBetween(from: LocalDate, to: LocalDate): IO[Set[LocalDate]] =
    sql"SELECT on_date FROM daily_budget_snapshots WHERE on_date >= $from AND on_date <= $to"
      .query[LocalDate]
      .to[List]
      .transact(xa)
      .map(_.toSet)

  override def findByDate(date: LocalDate): IO[Option[DailyBudgetSnapshot]] =
    (columns ++ fr"WHERE on_date = $date").query[DailyBudgetSnapshot].option.transact(xa)

  override def findAll: IO[List[DailyBudgetSnapshot]] =
    (columns ++ fr"ORDER BY on_date").query[DailyBudgetSnapshot].to[List].transact(xa)

  override def datesWithoutOutsideTerms: IO[Set[LocalDate]] =
    sql"SELECT on_date FROM daily_budget_snapshots WHERE from_savings_cents IS NULL".query[LocalDate].to[List].transact(xa).map(_.toSet)

  override def fillOutsideTerms(terms: List[(LocalDate, Long, Long, Long)]): IO[Unit] =
    Update[(Long, Long, Long, LocalDate)](
      "UPDATE daily_budget_snapshots SET ledger_cents = ?, from_savings_cents = ?, unplanned_income_cents = ? " +
        "WHERE on_date = ? AND from_savings_cents IS NULL",
    ).updateMany(terms.map { case (day, ledger, fromSavings, income) => (ledger, fromSavings, income, day) }).transact(xa).void

  override def rederive(terms: List[DerivedTerms]): IO[Unit] =
    // `IS NOT`, because the three ledger columns of an old row can be NULL. The comparison keeps the steady state, where nothing changed, free of
    // writes.
    terms
      .traverse_ { t =>
        sql"""UPDATE daily_budget_snapshots
            SET planned_to_pay_cents = ${t.plannedToPayCents}, planned_to_receive_cents = ${t.plannedToReceiveCents},
                budgets_to_spend_cents = ${t.budgetsToSpendCents}, budgets_to_receive_cents = ${t.budgetsToReceiveCents},
                ledger_cents = ${t.ledgerCents}, from_savings_cents = ${t.fromSavingsCents}, unplanned_income_cents = ${t.unplannedIncomeCents}
            WHERE on_date = ${t.day}
              AND (planned_to_pay_cents IS NOT ${t.plannedToPayCents} OR planned_to_receive_cents IS NOT ${t.plannedToReceiveCents}
                OR budgets_to_spend_cents IS NOT ${t.budgetsToSpendCents} OR budgets_to_receive_cents IS NOT ${t.budgetsToReceiveCents}
                OR ledger_cents IS NOT ${t.ledgerCents} OR from_savings_cents IS NOT ${t.fromSavingsCents}
                OR unplanned_income_cents IS NOT ${t.unplannedIncomeCents})""".update.run
      }
      .transact(xa)
      .unlessA(terms.isEmpty)

  private val columns = Fragment.const(s"SELECT $SelectList FROM daily_budget_snapshots")
}

/** The terms of one day that are computed from the record (the plan, the budgets, the bank ledger) and today's configuration, as opposed to the
  * balances, which are read on the day.
  */
final case class DerivedTerms(
    day: LocalDate,
    plannedToPayCents: Long,
    plannedToReceiveCents: Long,
    budgetsToSpendCents: Long,
    budgetsToReceiveCents: Long,
    ledgerCents: Long,
    fromSavingsCents: Long,
    unplannedIncomeCents: Long,
)

object DailyBudgetSnapshotRepositoryImpl {

  /** Column order, stated once: it is both what a row is read back as and what [[DailyBudgetSnapshot]]'s fields are written in. */
  private val Columns =
    List(
      "on_date",
      "captured_at",
      "period_id",
      "currency",
      "spendable_cents",
      "planned_to_pay_cents",
      "planned_to_receive_cents",
      "budgets_to_spend_cents",
      "budgets_to_receive_cents",
      "savings_cents",
      "ledger_cents",
      "from_savings_cents",
      "unplanned_income_cents",
      "days_remaining",
      "source",
    )

  private val ColumnList = Columns.mkString(", ")

  /** A row from before the outside-money terms has NULL there until the service fills it; it reads as zero in the meantime. */
  private val SelectList = Columns
    .map {
      case c @ ("ledger_cents" | "from_savings_cents" | "unplanned_income_cents") => s"COALESCE($c, 0)"
      case c                                                                      => c
    }
    .mkString(", ")

  private val Placeholders = Columns.map(_ => "?").mkString(", ")
}
