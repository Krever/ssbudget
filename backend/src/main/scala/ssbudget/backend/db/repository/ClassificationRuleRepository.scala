package ssbudget.backend.db.repository

import cats.effect.IO
import cats.implicits.*
import doobie.*
import doobie.implicits.*
import ssbudget.backend.db.DoobieMeta.given
import ssbudget.shared.model.*

trait ClassificationRuleRepository {
  def create(rule: ClassificationRule): IO[Unit]
  def findAll: IO[List[ClassificationRule]]
  def findById(id: ClassificationRuleId): IO[Option[ClassificationRule]]
  def update(id: ClassificationRuleId, name: String, categoryId: CategoryId, criteria: List[RuleCriterion]): IO[Unit]
  def delete(id: ClassificationRuleId): IO[Unit]

  /** Rewrite priorities so each id takes the priority of its position in the list (0-based). Ids not present are left untouched. */
  def reorder(orderedIds: List[ClassificationRuleId]): IO[Unit]

  /** Priority to assign the next appended rule (max + 1, or 0 when empty). */
  def nextPriority: IO[Int]

  /** Drop all rules targeting a category (used when the category itself is deleted). */
  def deleteByCategory(categoryId: CategoryId): IO[Unit]

  /** Remove every rule (used by a replace-mode import). */
  def deleteAll: IO[Unit]
}

class ClassificationRuleRepositoryImpl(xa: Transactor[IO]) extends ClassificationRuleRepository {

  private val columns = fr"id, name, category_id, priority, criteria, created_at"

  override def create(rule: ClassificationRule): IO[Unit] =
    sql"""
      INSERT INTO classification_rules (id, name, category_id, priority, criteria, created_at)
      VALUES (${rule.id}, ${rule.name}, ${rule.categoryId}, ${rule.priority}, ${rule.criteria}, ${rule.createdAt})
    """.update.run.transact(xa).void

  override def findAll: IO[List[ClassificationRule]] =
    (fr"SELECT" ++ columns ++ fr"FROM classification_rules ORDER BY priority, created_at").query[ClassificationRule].to[List].transact(xa)

  override def findById(id: ClassificationRuleId): IO[Option[ClassificationRule]] =
    (fr"SELECT" ++ columns ++ fr"FROM classification_rules WHERE id = $id").query[ClassificationRule].option.transact(xa)

  override def update(id: ClassificationRuleId, name: String, categoryId: CategoryId, criteria: List[RuleCriterion]): IO[Unit] =
    sql"UPDATE classification_rules SET name = $name, category_id = $categoryId, criteria = $criteria WHERE id = $id".update.run.transact(xa).void

  override def reorder(orderedIds: List[ClassificationRuleId]): IO[Unit] =
    orderedIds.zipWithIndex
      .traverse_ { case (id, idx) => sql"UPDATE classification_rules SET priority = $idx WHERE id = $id".update.run }
      .transact(xa)

  override def nextPriority: IO[Int] =
    sql"SELECT COALESCE(MAX(priority) + 1, 0) FROM classification_rules".query[Int].unique.transact(xa)

  override def deleteByCategory(categoryId: CategoryId): IO[Unit] =
    sql"DELETE FROM classification_rules WHERE category_id = $categoryId".update.run.transact(xa).void

  override def delete(id: ClassificationRuleId): IO[Unit] =
    sql"DELETE FROM classification_rules WHERE id = $id".update.run.transact(xa).void

  override def deleteAll: IO[Unit] =
    sql"DELETE FROM classification_rules".update.run.transact(xa).void
}
