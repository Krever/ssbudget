package ssbudget.backend.db.repository

import doobie.implicits.*
import ssbudget.backend.db.DoobieMeta.given
import ssbudget.backend.db.Repositories
import ssbudget.shared.model.*

import java.time.Instant

/** The writes of several steps are one transaction each: a failure in a later step leaves nothing of the earlier steps. */
class AtomicWritesSpec extends RepositorySpec {

  private val now = Instant.parse("2026-03-01T10:00:00Z")

  private def rule(id: String, category: String, priority: Int): ClassificationRule =
    ClassificationRule(ClassificationRuleId(id), s"rule $id", CategoryId(category), priority, List(RuleCriterion.Direction(outflow = true)), now)

  private def ruleIds = sql"SELECT id FROM classification_rules ORDER BY priority".query[String].to[List].transact(xa)

  "a rule import that fails leaves the rules and the categories as they were, also with `replace`" in {
    val repos = Repositories.fromTransactor(xa)
    for {
      _          <- repos.categories.create(Category(CategoryId("food"), "Food", None))
      _          <- repos.classificationRules.create(rule("old", "food", 0))
      // The second rule names a category that does not exist: the database refuses it, after the delete and the first inserts.
      failed     <- repos.classificationRules
                      .importAll(replace = true, List(Category(CategoryId("new"), "New", None)), _ => List(rule("a", "new", 0), rule("b", "missing", 1)))
                      .attempt
      rules      <- ruleIds
      categories <- repos.categories.findAll
      _          <- repos.classificationRules.importAll(replace = true, List(Category(CategoryId("new"), "New", None)), base => List(rule("a", "new", base)))
      after      <- ruleIds
    } yield {
      failed.isLeft shouldBe true
      rules shouldBe List("old")
      categories.map(_.id.value) shouldBe List("food")
      after shouldBe List("a")
    }
  }

  "an import that adds to the rules puts them after the rules that exist" in {
    val repos = Repositories.fromTransactor(xa)
    for {
      _     <- repos.categories.create(Category(CategoryId("food"), "Food", None))
      _     <- repos.classificationRules.create(rule("old", "food", 0))
      _     <- repos.classificationRules.importAll(replace = false, Nil, base => List(rule("a", "food", base), rule("b", "food", base + 1)))
      rules <- ruleIds
    } yield rules shouldBe List("old", "a", "b")
  }

  "a rule change writes the rule and the new order together, or neither" in {
    val repos = Repositories.fromTransactor(xa)
    for {
      _      <- repos.categories.create(Category(CategoryId("food"), "Food", None))
      _      <- repos.classificationRules.create(rule("x", "food", 0))
      _      <- repos.classificationRules.create(rule("y", "food", 1))
      _      <- repos.classificationRules.write(RuleWrite.Create(rule("z", "food", 2)), Some(List("z", "x", "y").map(ClassificationRuleId(_))))
      moved  <- ruleIds
      // A rule for a category that does not exist is refused; the order that came with it is not written.
      failed <- repos.classificationRules
                  .write(RuleWrite.Update(rule("x", "missing", 0)), Some(List("y", "x", "z").map(ClassificationRuleId(_))))
                  .attempt
      kept   <- ruleIds
    } yield {
      moved shouldBe List("z", "x", "y")
      failed.isLeft shouldBe true
      kept shouldBe List("z", "x", "y")
    }
  }

  "a category goes with its rules and overrides, and its transactions stay with no category" in {
    val repos = Repositories.fromTransactor(xa)
    for {
      _     <- repos.categories.create(Category(CategoryId("food"), "Food", None))
      _     <- repos.classificationRules.create(rule("r", "food", 0))
      _     <- sql"""INSERT INTO bank_transactions
                         (id, connection_id, eb_account_uid, dedup_key, amount_cents, currency, status, booked_at, raw_json, imported_at,
                          category_id, category_source)
                       VALUES ('t1', 'c', 'uid', 'k', -100, 'PLN', 'booked', $now, '{}', $now, 'food', 'rule')""".update.run.transact(xa)
      _     <- repos.categories.deleteWithDependents(CategoryId("food"))
      rules <- ruleIds
      tx    <- sql"SELECT category_id, category_source FROM bank_transactions".query[(Option[String], Option[String])].to[List].transact(xa)
      cats  <- repos.categories.findAll
    } yield {
      rules shouldBe Nil
      tx shouldBe List((None, None))
      cats shouldBe Nil
    }
  }

  "an account is made with its first balance record, and goes with its history" in {
    val repos   = Repositories.fromTransactor(xa)
    val account = spendingAccount("acc", "Main", Currency.PLN)
    for {
      _       <- repos.accounts.createWithOpeningBalance(account, now)
      history <- repos.balanceSnapshots.findByAccount(account.id)
      stored  <- repos.accounts.findById(account.id)
      _       <- repos.accounts.deleteWithHistory(account.id)
      gone    <- repos.accounts.findById(account.id)
      left    <- sql"SELECT COUNT(*) FROM balance_snapshots".query[Int].unique.transact(xa)
    } yield {
      history.map(_.amount) shouldBe List(0L)
      stored.flatMap(_.balanceUpdatedAt) shouldBe Some(now)
      gone shouldBe None
      left shouldBe 0
    }
  }
}
