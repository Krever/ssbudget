package ssbudget.backend.db.repository

import cats.effect.IO
import doobie.*
import doobie.implicits.*
import ssbudget.backend.db.DoobieMeta.given
import ssbudget.shared.model.*

trait CategoryRepository {
  def create(category: Category): IO[Unit]
  def findAll: IO[List[Category]]
  def findById(id: CategoryId): IO[Option[Category]]
  def update(category: Category): IO[Unit]
  def delete(id: CategoryId): IO[Unit]
}

class CategoryRepositoryImpl(xa: Transactor[IO]) extends CategoryRepository {

  private val columns = fr"id, name, color, budget_type"

  override def create(category: Category): IO[Unit] =
    sql"INSERT INTO categories (id, name, color, budget_type) VALUES (${category.id}, ${category.name}, ${category.color}, ${category.budgetType})".update.run
      .transact(
        xa,
      )
      .void

  override def findAll: IO[List[Category]] =
    (fr"SELECT" ++ columns ++ fr"FROM categories ORDER BY name").query[Category].to[List].transact(xa)

  override def findById(id: CategoryId): IO[Option[Category]] =
    (fr"SELECT" ++ columns ++ fr"FROM categories WHERE id = $id").query[Category].option.transact(xa)

  override def update(category: Category): IO[Unit] =
    sql"UPDATE categories SET name = ${category.name}, color = ${category.color}, budget_type = ${category.budgetType} WHERE id = ${category.id}".update.run
      .transact(xa)
      .void

  override def delete(id: CategoryId): IO[Unit] =
    sql"DELETE FROM categories WHERE id = $id".update.run.transact(xa).void
}
