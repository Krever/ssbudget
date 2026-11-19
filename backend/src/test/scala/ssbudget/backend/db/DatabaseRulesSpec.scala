package ssbudget.backend.db

import cats.effect.IO
import doobie.implicits.*
import ssbudget.backend.db.DoobieMeta.given
import ssbudget.backend.db.repository.RepositorySpec

import java.time.Instant

/** Two rules of the database that every repository depends on: references are enforced, and an instant has one text form. */
class DatabaseRulesSpec extends RepositorySpec {

  "foreign keys are on for the connections of the application" in {
    sql"PRAGMA foreign_keys".query[Int].unique.transact(xa).map(_ shouldBe 1)
  }

  "a row cannot name a parent that is not there" in {
    sql"""INSERT INTO classification_rules (id, name, category_id, criteria, priority, created_at)
          VALUES ('r1', 'rule', 'no-such-category', '[]', 0, ${Instant.now()})""".update.run.transact(xa).attempt.map { result =>
      result.left.map(_.getMessage).left.getOrElse("") should include("FOREIGN KEY")
    }
  }

  "a rate can be stored for a currency that is not enabled, and a transaction can outlive its connection" in {
    for {
      _ <- sql"""INSERT INTO exchange_rates (from_currency, to_currency, rate, fetched_at) VALUES ('ISK', 'PLN', 300, ${Instant.now()})""".update.run
             .transact(xa)
      _ <- sql"""INSERT INTO bank_transactions
                   (id, connection_id, eb_account_uid, dedup_key, amount_cents, currency, status, booked_at, raw_json, imported_at)
                 VALUES ('t1', 'a-connection-that-is-gone', 'uid', 'k', -100, 'PLN', 'booked', ${Instant.now()}, '{}', ${Instant.now()})""".update.run
             .transact(xa)
      n <- sql"SELECT COUNT(*) FROM bank_transactions".query[Int].unique.transact(xa)
    } yield n shouldBe 1
  }

  "an instant is stored in one form of 24 characters, so that the order of the text is the order of time" in {
    // Java prints these four with 0, 3, 6 and 9 digits of the second. As such text, the first would sort AFTER the other three.
    val instants =
      List("2026-03-01T10:00:05Z", "2026-03-01T10:00:05.300Z", "2026-03-01T10:00:05.300400Z", "2026-03-01T10:00:05.900000001Z").map(Instant.parse)
    instants.map(DoobieMeta.instantToText) shouldBe
      List("2026-03-01T10:00:05.000Z", "2026-03-01T10:00:05.300Z", "2026-03-01T10:00:05.300Z", "2026-03-01T10:00:05.900Z")
    IO.pure(instants.map(DoobieMeta.instantToText).sorted shouldBe instants.sorted.map(DoobieMeta.instantToText))
  }
}
