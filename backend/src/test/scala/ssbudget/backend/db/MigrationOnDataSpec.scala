package ssbudget.backend.db

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import doobie.implicits.*
import org.flywaydb.core.Flyway
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files
import java.sql.DriverManager

/** The migrations, run on a database that HOLDS DATA.
  *
  * Every other spec starts from an empty database, where a migration that changes rows or makes a table again has nothing to break. Here a database
  * is brought to an old schema version, filled with rows in the forms that such a database really held (instants of different widths, a rate for a
  * currency that is not enabled, a transaction with a category), and then migrated to the schema of this build as the application does it at start.
  *
  * A new migration that touches rows adds its own rows to `seed` and its own checks here. Move `seedVersion` forward only together with the seed: the
  * seed is written for the schema of that version.
  */
class MigrationOnDataSpec extends AsyncFreeSpec with AsyncIOSpec with Matchers {

  private val seedVersion = "31"

  private val seed = List(
    "INSERT OR IGNORE INTO currency_settings (code, name, is_primary, enabled_at) VALUES ('PLN', 'Polish Zloty', 1, '2026-01-29T22:42:05Z')",
    """INSERT INTO accounts (id, name, currency, role, balance_cents, balance_source, balance_updated_at)
       VALUES ('acc', 'Main', 'PLN', 'spending', 150000, 'bank', '2026-11-18T16:39:19.060638Z')""",
    // Java printed an instant with 0, 3, 6 or 9 digits of the second: all four forms, inside one second.
    "INSERT INTO balance_snapshots (id, account_id, amount, currency, recorded_at) VALUES ('s0', 'acc', 100, 'PLN', '2026-03-01T10:00:05Z')",
    "INSERT INTO balance_snapshots (id, account_id, amount, currency, recorded_at) VALUES ('s3', 'acc', 200, 'PLN', '2026-03-01T10:00:05.300Z')",
    "INSERT INTO balance_snapshots (id, account_id, amount, currency, recorded_at) VALUES ('s6', 'acc', 300, 'PLN', '2026-03-01T10:00:05.600400Z')",
    "INSERT INTO balance_snapshots (id, account_id, amount, currency, recorded_at) VALUES ('s9', 'acc', 400, 'PLN', '2026-03-01T10:00:05.900000001Z')",
    "INSERT INTO categories (id, name) VALUES ('food', 'Food')",
    """INSERT INTO bank_connections (id, aspsp_name, aspsp_country, status, created_at)
       VALUES ('conn', 'Bank', 'PL', 'active', '2026-10-12T10:32:13.145517Z')""",
    """INSERT INTO bank_transactions
         (id, connection_id, eb_account_uid, dedup_key, amount_cents, currency, status, booked_at, raw_json, imported_at, category_id, note)
       VALUES ('t1', 'conn', 'uid', 'k1', -1234, 'PLN', 'booked', '2026-03-02T00:00:00Z', '{"a":1}', '2026-07-10T20:46:09.174256Z', 'food',
               'a note')""",
    // A rate for a currency that is not in currency_settings: the application stores such rates, and V33 makes the schema allow them.
    "INSERT INTO exchange_rates (from_currency, to_currency, rate, fetched_at) VALUES ('ISK', 'PLN', 290, '2026-01-29T23:05:39.628299477Z')",
    // An older fetch of the same day: V36 keeps one rate for each pair and day, the newest.
    "INSERT INTO exchange_rates (from_currency, to_currency, rate, fetched_at) VALUES ('ISK', 'PLN', 280, '2026-01-29T10:00:00Z')",
    "INSERT INTO exchange_rates (from_currency, to_currency, rate, fetched_at) VALUES ('ISK', 'PLN', 300, '2026-02-17T10:00:00Z')",
    "INSERT INTO periods (id, started_at, expected_end, ended_at, planned_saving_cents) VALUES ('p1', '2026-02-25T08:54:10Z', '2026-03-25', NULL, 0)",
  )

  /** A database file at `seedVersion` with the seed rows, made with plain connections as an old build of the application left it. */
  private def seededDatabase: IO[String] = IO.blocking {
    val file = Files.createTempFile("ssbudget-migration", ".db")
    file.toFile.deleteOnExit()
    val url  = s"jdbc:sqlite:${file.toAbsolutePath}"
    val _    = Flyway.configure().dataSource(url, null, null).target(seedVersion).load().migrate()
    val conn = DriverManager.getConnection(url)
    try {
      val statement = conn.createStatement()
      seed.foreach(sql => statement.executeUpdate(sql))
      statement.close()
    } finally conn.close()
    url
  }

  "a database with data comes to the schema of this build with its rows as they were" in {
    seededDatabase.flatMap { url =>
      Database.migrateAndTransactor(url).use { xa =>
        (for {
          snapshots  <- sql"SELECT id, amount, recorded_at FROM balance_snapshots ORDER BY recorded_at".query[(String, Long, String)].to[List]
          tx         <- sql"""SELECT connection_id, amount_cents, booked_at, imported_at, category_id, raw_json, note
                              FROM bank_transactions""".query[(String, Long, String, String, Option[String], String, Option[String])].to[List]
          rates      <- sql"SELECT from_currency, on_date, rate, fetched_at FROM exchange_rates ORDER BY on_date"
                          .query[(String, String, Long, String)]
                          .to[List]
          period     <- sql"SELECT started_at, expected_end FROM periods".query[(String, String)].unique
          viewRows   <- sql"SELECT COUNT(*) FROM v_transactions".query[Int].unique
          keysOn     <- sql"PRAGMA foreign_keys".query[Int].unique
          violations <- sql"SELECT COUNT(*) FROM pragma_foreign_key_check".query[Int].unique
        } yield {
          // V32: one text form of 24 characters. The four records inside one second are now in the order of time.
          snapshots shouldBe List(
            ("s0", 100L, "2026-03-01T10:00:05.000Z"),
            ("s3", 200L, "2026-03-01T10:00:05.300Z"),
            ("s6", 300L, "2026-03-01T10:00:05.600Z"),
            ("s9", 400L, "2026-03-01T10:00:05.900Z"),
          )
          period shouldBe (("2026-02-25T08:54:10.000Z", "2026-03-25"))
          // V33 made two tables again: every value of the rows is as it was, but for the form of the instants.
          tx shouldBe List(("conn", -1234L, "2026-03-02T00:00:00.000Z", "2026-07-10T20:46:09.174Z", Some("food"), """{"a":1}""", Some("a note")))
          // V36: the day of each rate is the day it was fetched on, one rate for each pair and day.
          rates shouldBe List(("ISK", "2026-01-29", 290L, "2026-01-29T23:05:39.628Z"), ("ISK", "2026-02-17", 300L, "2026-02-17T10:00:00.000Z"))
          // V33 dropped the reporting views. Flyway did not make them again here: the seed step already ran the views script in its present
          // text, so for Flyway nothing changed. `Database.restoreMissingViews` made them.
          viewRows shouldBe 1
          keysOn shouldBe 1
          violations shouldBe 0
        }).transact(xa)
      }
    }
  }
}
