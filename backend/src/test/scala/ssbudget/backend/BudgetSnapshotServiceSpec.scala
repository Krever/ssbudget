package ssbudget.backend

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import doobie.*
import doobie.implicits.*
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers
import ssbudget.backend.db.DoobieMeta.given
import ssbudget.backend.db.{Database, Repositories}
import ssbudget.backend.service.BudgetSnapshotService
import ssbudget.shared.model.*

import java.nio.file.Files
import java.time.{Instant, LocalDate, ZoneOffset}

/** The daily free-money components, over a database built to have a knowable answer on each day.
  *
  * The fixture is a single period with one spending account, one planned expense settled part-way through, and a bank ledger that moves the balance
  * on days no snapshot was taken — which is the situation the reconstruction exists for.
  */
class BudgetSnapshotServiceSpec extends AsyncFreeSpec with AsyncIOSpec with Matchers {

  private val today       = LocalDate.now(ZoneOffset.UTC)
  private val periodStart = today.minusDays(10)

  private def at(day: LocalDate, hour: Int = 12): Instant = day.atStartOfDay(ZoneOffset.UTC).plusHours(hour).toInstant

  private def withFixture[A](test: (BudgetSnapshotService, Repositories, Transactor[IO]) => IO[A]): IO[A] = {
    val file = Files.createTempFile("ssbudget-snapshot-test-", ".db")
    Files.delete(file)
    Database
      .migrateAndTransactor(s"jdbc:sqlite:${file.toAbsolutePath}")
      .use { xa =>
        val repos = Repositories.fromTransactor(xa)
        seed(xa) *> test(new BudgetSnapshotService(repos), repos, xa)
      }
      .guarantee(IO.blocking(Files.deleteIfExists(file)).void)
  }

  /** 1,000 PLN on the account, a 300 PLN bill settled on day 5, and 150 PLN of card spend on day 7 that no balance snapshot ever saw. */
  private def seed(xa: Transactor[IO]): IO[Unit] = {
    val insert = for {
      _ <- sql"""INSERT INTO accounts (id, name, currency, role, balance_cents, balance_source, balance_updated_at)
                 VALUES ('acc1', 'Main', 'PLN', 'spending', 85000, 'bank', ${at(today)})""".update.run
      _ <- sql"""INSERT INTO periods (id, started_at, ended_at) VALUES ('per1', ${at(periodStart, 9)}, NULL)""".update.run
      _ <- sql"""INSERT INTO expense_definitions (id, name, item_type, estimate_cents, currency)
                 VALUES ('def1', 'Rent', 'planned_expense', 30000, 'PLN')""".update.run
      _ <- sql"""INSERT INTO expense_records (id, period_id, expense_def_id, paid_amount, paid_at, settled)
                 VALUES ('rec1', 'per1', 'def1', 30000, ${at(periodStart.plusDays(5), 10)}, 1)""".update.run
      // A 100 EUR bill, with a record, and 4 PLN/EUR on the books: it must be reserved as 400 PLN, not as 100.
      _ <- sql"""INSERT INTO expense_definitions (id, name, item_type, estimate_cents, currency)
                 VALUES ('def2', 'Hosting', 'planned_expense', 10000, 'EUR')""".update.run
      _ <- sql"""INSERT INTO expense_records (id, period_id, expense_def_id, paid_amount, paid_at, settled)
                 VALUES ('rec2', 'per1', 'def2', NULL, NULL, 0)""".update.run
      _ <- sql"""INSERT INTO exchange_rates (from_currency, to_currency, rate, fetched_at)
                 VALUES ('EUR', 'PLN', 40000, ${at(periodStart, 1)})""".update.run
      // A definition with no record in this period — never declared for it, so it is not owed in it.
      _ <- sql"""INSERT INTO expense_definitions (id, name, item_type, estimate_cents, currency)
                 VALUES ('def3', 'Added later', 'planned_expense', 90000, 'PLN')""".update.run
      // The only balance ever recorded: 1,000 PLN on the day the period opened.
      _ <- sql"""INSERT INTO balance_snapshots (id, account_id, amount, currency, recorded_at)
                 VALUES ('snap1', 'acc1', 100000, 'PLN', ${at(periodStart, 9)})""".update.run
      // A bank ledger for that account, so days after the snapshot are knowable rather than flat.
      _ <- sql"""INSERT INTO bank_connections (id, aspsp_name, aspsp_country, status, created_at)
                 VALUES ('conn1', 'Test Bank', 'PL', 'active', ${at(periodStart)})""".update.run
      _ <- sql"""INSERT INTO bank_account_links (id, connection_id, eb_account_uid, link_target_kind, link_target_id)
                 VALUES ('link1', 'conn1', 'uid1', 'account', 'acc1')""".update.run
      _ <- sql"""INSERT INTO bank_transactions
                   (id, connection_id, eb_account_uid, dedup_key, amount_cents, currency, status, booked_at, raw_json, imported_at, is_internal)
                 VALUES ('tx1', 'conn1', 'uid1', 'd1', -15000, 'PLN', 'booked', ${at(periodStart.plusDays(7), 0)}, '{}', ${at(
               today,
             )}, 0)""".update.run
      // A 1,000 PLN subscription-style budget, drawn down by 100 EUR of spend on day 2. The spend sits on an unlinked bank account so it moves
      // the category's budget without also moving a balance.
      _ <- sql"""INSERT INTO categories (id, name, color, budget_type, budget_method, budget_fixed_cents)
                 VALUES ('cat1', 'Travel', '#888', 'subscription', 'fixed', 100000)""".update.run
      _ <- sql"""INSERT INTO bank_transactions
                   (id, connection_id, eb_account_uid, dedup_key, amount_cents, currency, status, booked_at, raw_json, imported_at,
                    is_internal, category_id)
                 VALUES ('tx2', 'conn1', 'uid2', 'd2', -10000, 'EUR', 'booked', ${at(periodStart.plusDays(2), 0)}, '{}', ${at(
               today,
             )}, 0, 'cat1')""".update.run
    } yield ()
    insert.transact(xa).void
  }

  private def freeOn(service: BudgetSnapshotService, day: LocalDate): IO[DailyBudgetSnapshot] =
    service.componentsAsOf(day).map(_.getOrElse(fail(s"no components for $day")))

  "A day's components" - {
    "reserve a planned item in full before it was paid" in {
      withFixture { (service, _, _) =>
        freeOn(service, periodStart.plusDays(1)).asserting { s =>
          s.spendableCents shouldBe 100000L
          s.plannedToPayCents shouldBe 70000L // 300 PLN rent + 100 EUR hosting at 4.0
          s.source shouldBe SnapshotSource.Reconstructed
        }
      }
    }

    "release it from the day it was settled" in {
      withFixture { (service, _, _) =>
        for {
          before <- freeOn(service, periodStart.plusDays(4))
          after  <- freeOn(service, periodStart.plusDays(5))
        } yield {
          before.plannedToPayCents shouldBe 70000L
          after.plannedToPayCents shouldBe 40000L // only the unpaid EUR bill is left
        }
      }
    }

    "carry the balance through transactions on days no snapshot was taken" in {
      withFixture { (service, _, _) =>
        for {
          before <- freeOn(service, periodStart.plusDays(6))
          after  <- freeOn(service, periodStart.plusDays(8))
        } yield {
          before.spendableCents shouldBe 100000L // the snapshot, unmoved
          after.spendableCents shouldBe 85000L   // less the 150 PLN booked on day 7
        }
      }
    }

    "reserve a foreign-currency item in the primary currency" in {
      withFixture { (service, _, _) =>
        freeOn(service, periodStart.plusDays(6)).asserting { s =>
          // 100 EUR at 4.0 PLN/EUR. Counting it as 10000 raw cents would understate what is owed fourfold.
          s.plannedToPayCents shouldBe 40000L
          s.currency shouldBe Currency.PLN
        }
      }
    }

    "ignore a definition that has no record in the period" in {
      withFixture { (service, _, _) =>
        freeOn(service, periodStart.plusDays(6)).asserting { s =>
          // 'Added later' is a 900 PLN expense with no record here. A record is opened for every definition when a period
          // starts, so its absence is the app's own statement that the item did not exist then.
          s.plannedToPayCents shouldBe 40000L
        }
      }
    }

    "draw a category budget down by foreign spend converted to the primary currency" in {
      withFixture { (service, _, _) =>
        for {
          before <- freeOn(service, periodStart.plusDays(1))
          after  <- freeOn(service, periodStart.plusDays(3))
        } yield {
          // 1,000 PLN budget, untouched, then drawn down by 100 EUR at 4.0 — 600 PLN left, not the 900 that counting
          // the EUR cents raw would give.
          before.budgetsToSpendCents shouldBe 100000L
          after.budgetsToSpendCents shouldBe 60000L
        }
      }
    }

    "count today from the account's live balance, and say so" in {
      withFixture { (service, _, _) =>
        freeOn(service, today).asserting { s =>
          s.spendableCents shouldBe 85000L
          s.source shouldBe SnapshotSource.Live
          s.daysRemaining shouldBe Some(Period.daysRemaining(at(periodStart, 9), today))
        }
      }
    }
  }

  "Keeping the table up to date" - {
    "writes every day from the first period to today, once" in {
      withFixture { (service, repos, _) =>
        for {
          _     <- service.ensureUpToDate
          rows  <- repos.dailyBudgetSnapshots.findAll
          _     <- service.ensureUpToDate
          again <- repos.dailyBudgetSnapshots.findAll
        } yield {
          rows.map(_.onDate) shouldBe (0 to 10).map(periodStart.plusDays(_)).toList
          rows.count(_.source == SnapshotSource.Live) shouldBe 1
          again.size shouldBe rows.size
        }
      }
    }

    "never lets a reconstructed row replace a measurement" in {
      withFixture { (service, repos, _) =>
        val day = periodStart.plusDays(2)
        for {
          measured <- freeOn(service, day).map(_.copy(source = SnapshotSource.Live, spendableCents = 123456L))
          _        <- repos.dailyBudgetSnapshots.record(measured)
          _        <- service.ensureUpToDate
          stored   <- repos.dailyBudgetSnapshots.findByDate(day)
        } yield stored.map(_.spendableCents) shouldBe Some(123456L)
      }
    }
  }

  "The SQL view and the Scala formula" - {
    "agree on free money for every stored row" in {
      withFixture { (service, repos, xa) =>
        for {
          _      <- service.ensureUpToDate
          rows   <- repos.dailyBudgetSnapshots.findAll
          fromDb <- viewFreeMoney(xa)
        } yield {
          fromDb should not be empty
          fromDb.toMap shouldBe rows.map(r => r.onDate.toString -> r.freeMoneyCents).toMap
        }
      }
    }
  }

  /** `v_daily_budget.free_money` is in major units; read it back as cents so it compares to the Scala figure exactly. */
  private def viewFreeMoney(xa: Transactor[IO]): IO[List[(String, Long)]] =
    sql"SELECT on_date, CAST(ROUND(free_money * 100) AS INTEGER) FROM v_daily_budget".query[(String, Long)].to[List].transact(xa)
}
