package ssbudget.backend.db.repository

import ssbudget.shared.model.*

class CategoryRepositorySpec extends RepositorySpec {

  private def category(id: String, name: String, color: Option[String] = None): Category =
    Category(CategoryId(id), name, color)

  "create and findById returns the category" in {
    val repo = new CategoryRepositoryImpl(xa)
    val c    = category("c-1", "Groceries", Some("#0a0"))
    for {
      _     <- repo.create(c)
      found <- repo.findById(CategoryId("c-1"))
    } yield found shouldBe Some(c)
  }

  "findAll returns categories ordered by name" in {
    val repo = new CategoryRepositoryImpl(xa)
    for {
      _   <- repo.create(category("c-1", "Zebra"))
      _   <- repo.create(category("c-2", "Alpha"))
      all <- repo.findAll
    } yield all.map(_.name) shouldBe List("Alpha", "Zebra")
  }

  "update changes name and color" in {
    val repo = new CategoryRepositoryImpl(xa)
    for {
      _     <- repo.create(category("c-1", "Old", None))
      _     <- repo.update(category("c-1", "New", Some("#f00")))
      found <- repo.findById(CategoryId("c-1"))
    } yield found shouldBe Some(category("c-1", "New", Some("#f00")))
  }

  "delete removes the category" in {
    val repo = new CategoryRepositoryImpl(xa)
    for {
      _     <- repo.create(category("c-1", "Temp"))
      _     <- repo.delete(CategoryId("c-1"))
      found <- repo.findById(CategoryId("c-1"))
    } yield found shouldBe None
  }
}
