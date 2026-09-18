package ssbudget.backend.db.repository

import cats.effect.IO
import doobie.*
import doobie.implicits.*
import ssbudget.backend.db.DoobieMeta.given
import ssbudget.shared.model.*

import java.time.Instant

/** Manual per-period overrides of a category budget's remaining amount. Rows are keyed by (period, category), so an override applies to one period
  * only and clearing it simply deletes the row.
  */
trait CategoryBudgetOverrideRepository {
  def findByPeriod(periodId: PeriodId): IO[Map[CategoryId, Long]]

  /** Every override ever set, with the instant it was set. The daily snapshot needs the timestamp: an override applies to the days from when the user
    * typed it, not to the whole period retrospectively.
    */
  def findAllStamped: IO[List[(PeriodId, CategoryId, Long, Instant)]]
  def upsert(periodId: PeriodId, categoryId: CategoryId, remainingCents: Long, updatedAt: Instant): IO[Unit]
  def delete(periodId: PeriodId, categoryId: CategoryId): IO[Unit]
  def deleteByCategory(categoryId: CategoryId): IO[Unit]
}

class CategoryBudgetOverrideRepositoryImpl(xa: Transactor[IO]) extends CategoryBudgetOverrideRepository {

  override def findByPeriod(periodId: PeriodId): IO[Map[CategoryId, Long]] =
    sql"SELECT category_id, remaining_cents FROM category_budget_overrides WHERE period_id = $periodId"
      .query[(CategoryId, Long)]
      .to[List]
      .transact(xa)
      .map(_.toMap)

  override def findAllStamped: IO[List[(PeriodId, CategoryId, Long, Instant)]] =
    sql"SELECT period_id, category_id, remaining_cents, updated_at FROM category_budget_overrides"
      .query[(PeriodId, CategoryId, Long, Instant)]
      .to[List]
      .transact(xa)

  override def upsert(periodId: PeriodId, categoryId: CategoryId, remainingCents: Long, updatedAt: Instant): IO[Unit] =
    sql"""
      INSERT INTO category_budget_overrides (period_id, category_id, remaining_cents, updated_at)
      VALUES ($periodId, $categoryId, $remainingCents, $updatedAt)
      ON CONFLICT (period_id, category_id) DO UPDATE SET remaining_cents = $remainingCents, updated_at = $updatedAt
    """.update.run.transact(xa).void

  override def delete(periodId: PeriodId, categoryId: CategoryId): IO[Unit] =
    sql"DELETE FROM category_budget_overrides WHERE period_id = $periodId AND category_id = $categoryId".update.run.transact(xa).void

  override def deleteByCategory(categoryId: CategoryId): IO[Unit] =
    sql"DELETE FROM category_budget_overrides WHERE category_id = $categoryId".update.run.transact(xa).void
}
