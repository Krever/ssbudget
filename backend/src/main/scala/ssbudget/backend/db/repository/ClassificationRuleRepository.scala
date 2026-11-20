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

  /** One change of the rule list, in ONE transaction: the write of a rule, and the new order when the change moves a rule. With separate commits a
    * failure between the two left a new rule at the wrong place, where it can win over the rules it was put after.
    *
    * @param order
    *   every rule id in its new order; None when the stored priorities are already right
    */
  def write(change: RuleWrite, order: Option[List[ClassificationRuleId]]): IO[Unit]

  /** An import, in ONE transaction: with `replace` every rule goes first; then the new categories and the rules are made. `rules` gets the first free
    * priority and gives the rules to store. A failure thus leaves the rules as they were, and not an empty list.
    */
  def importAll(replace: Boolean, newCategories: List[Category], rules: Int => List[ClassificationRule]): IO[Unit]
}

/** What `ClassificationRuleRepository.write` does to one rule. */
enum RuleWrite {
  case Create(rule: ClassificationRule)
  case Update(rule: ClassificationRule) // name, category and criteria of the rule with this id
  case Delete(id: ClassificationRuleId)
}

class ClassificationRuleRepositoryImpl(xa: Transactor[IO]) extends ClassificationRuleRepository {

  private val columns = fr"id, name, category_id, priority, criteria, created_at"

  private def insert(rule: ClassificationRule): ConnectionIO[Unit] =
    sql"""
      INSERT INTO classification_rules (id, name, category_id, priority, criteria, created_at)
      VALUES (${rule.id}, ${rule.name}, ${rule.categoryId}, ${rule.priority}, ${rule.criteria}, ${rule.createdAt})
    """.update.run.void

  private def setOrder(orderedIds: List[ClassificationRuleId]): ConnectionIO[Unit] =
    orderedIds.zipWithIndex.traverse_ { case (id, idx) => sql"UPDATE classification_rules SET priority = $idx WHERE id = $id".update.run }

  override def create(rule: ClassificationRule): IO[Unit] = insert(rule).transact(xa)

  override def write(change: RuleWrite, order: Option[List[ClassificationRuleId]]): IO[Unit] = {
    val one: ConnectionIO[Unit] = change match {
      case RuleWrite.Create(rule) => insert(rule)
      case RuleWrite.Update(rule) =>
        sql"UPDATE classification_rules SET name = ${rule.name}, category_id = ${rule.categoryId}, criteria = ${rule.criteria} WHERE id = ${rule.id}".update.run.void
      case RuleWrite.Delete(id)   => sql"DELETE FROM classification_rules WHERE id = $id".update.run.void
    }
    (one *> order.traverse_(setOrder)).transact(xa)
  }

  override def importAll(replace: Boolean, newCategories: List[Category], rules: Int => List[ClassificationRule]): IO[Unit] =
    (for {
      _    <- if replace then sql"DELETE FROM classification_rules".update.run.void else ().pure[ConnectionIO]
      base <- sql"SELECT COALESCE(MAX(priority) + 1, 0) FROM classification_rules".query[Int].unique
      _    <- newCategories.traverse_(CategoryRepositoryImpl.insert)
      _    <- rules(base).traverse_(insert)
    } yield ()).transact(xa)

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

  override def delete(id: ClassificationRuleId): IO[Unit] =
    sql"DELETE FROM classification_rules WHERE id = $id".update.run.transact(xa).void

}
