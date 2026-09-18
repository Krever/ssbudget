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

  private val columns = Fragment.const(s"SELECT $ColumnList FROM daily_budget_snapshots")
}

object DailyBudgetSnapshotRepositoryImpl {

  /** Column order, stated once: it is both what a row is read back as and what [[DailyBudgetSnapshot]]'s fields are written in. */
  private val ColumnList =
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
      "days_remaining",
      "source",
    ).mkString(", ")

  private val Placeholders = ColumnList.split(", ").map(_ => "?").mkString(", ")
}
