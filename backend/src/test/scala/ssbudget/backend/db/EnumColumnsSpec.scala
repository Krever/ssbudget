package ssbudget.backend.db

import cats.implicits.*
import doobie.implicits.*
import ssbudget.backend.db.repository.RepositorySpec
import ssbudget.shared.model.*

/** The columns that hold a value of a fixed set accept only that set (V34), and the set in the database is the set in the code. */
class EnumColumnsSpec extends RepositorySpec {

  /** Each such column with the text values that the code can write: taken from the enumerations, so a new case shows here by itself. */
  private val columns: List[(String, String, Set[String])] = List(
    ("accounts", "role", AccountRole.values.map(AccountRole.asString).toSet),
    ("accounts", "balance_source", BalanceSource.values.map(BalanceSource.asString).toSet),
    ("bank_connections", "status", ConnectionStatus.values.map(ConnectionStatus.asString).toSet),
    (
      "bank_account_links",
      "link_target_kind",
      Set(BankLinkTarget.Unlinked, BankLinkTarget.Account(AccountId("a")), BankLinkTarget.CardGroupMember(CardGroupId("g"))).map(BankLinkTarget.kind),
    ),
    ("bank_transactions", "status", TransactionStatus.values.map(TransactionStatus.asString).toSet),
    ("bank_transactions", "category_source", CategorySource.values.map(CategorySource.asString).toSet),
    ("categories", "budget_type", CategoryBudgetType.values.map(CategoryBudgetType.asString).toSet),
    ("categories", "budget_method", CategoryBudgetMethod.values.map(CategoryBudgetMethod.asString).toSet),
    ("import_jobs", "status", ImportJobStatus.values.map(ImportJobStatus.asString).toSet),
    ("import_jobs", "kind", ImportJobKind.values.map(ImportJobKind.asString).toSet),
    ("daily_budget_snapshots", "source", SnapshotSource.values.map(SnapshotSource.asString).toSet),
  )

  "each column has its two triggers, and their list of values is the list of the enumeration in the code" in {
    columns
      .flatTraverse { case (table, column, expected) =>
        List("ins", "upd").traverse { kind =>
          val name = s"chk_${table}_${column}_$kind"
          sql"SELECT sql FROM sqlite_master WHERE type = 'trigger' AND name = $name".query[String].option.transact(xa).map { found =>
            // The values are the quoted words of the WHEN clause; the last quoted text of the trigger is its message.
            val listed = found.map(sql => "'([a-z_]+)'".r.findAllMatchIn(sql.takeWhile(_ != ';').split("BEGIN").head).map(_.group(1)).toSet)
            withClue(s"$name: ")(listed shouldBe Some(expected))
          }
        }
      }
      .map(_ => succeed)
  }

  "a value that is not of the set is refused, on insert and on update; NULL stays possible where the column allows it" in {
    def attempt(statement: doobie.Fragment) = statement.update.run.transact(xa).attempt.map(_.left.map(_.getMessage))
    for {
      badInsert <- attempt(sql"""INSERT INTO accounts (id, name, currency, role, balance_cents, balance_source)
                                 VALUES ('a1', 'A', 'PLN', 'checking', 0, 'manual')""")
      good      <- attempt(sql"""INSERT INTO accounts (id, name, currency, role, balance_cents, balance_source)
                                 VALUES ('a1', 'A', 'PLN', 'savings', 0, 'manual')""")
      badUpdate <- attempt(sql"UPDATE accounts SET balance_source = 'guess' WHERE id = 'a1'")
      otherCol  <- attempt(sql"UPDATE accounts SET name = 'B' WHERE id = 'a1'")
      nullable  <- attempt(sql"INSERT INTO categories (id, name, budget_type) VALUES ('c1', 'C', NULL)")
      badType   <- attempt(sql"UPDATE categories SET budget_type = 'weekly' WHERE id = 'c1'")
    } yield {
      badInsert.left.getOrElse("") should include("accounts.role")
      good shouldBe Right(1)
      badUpdate.left.getOrElse("") should include("accounts.balance_source")
      otherCol shouldBe Right(1)
      nullable shouldBe Right(1)
      badType.left.getOrElse("") should include("categories.budget_type")
    }
  }
}
