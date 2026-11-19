package ssbudget.backend

import cats.syntax.all.*
import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import doobie.*
import doobie.implicits.*
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers
import ssbudget.backend.db.DoobieMeta.given
import ssbudget.backend.db.{Database, Repositories}
import ssbudget.backend.service.PeriodService
import ssbudget.shared.model.*

import java.nio.file.Files
import java.time.{Instant, LocalDate, ZoneOffset}

/** The period lifecycle and the period reports, over a database built to have a knowable answer. */
class PeriodServiceSpec extends AsyncFreeSpec with AsyncIOSpec with Matchers {

  private def withDatabase[A](seed: ConnectionIO[Unit])(test: (PeriodService, Repositories, Transactor[IO]) => IO[A]): IO[A] = {
    val file = Files.createTempFile("ssbudget-period-test-", ".db")
    Files.delete(file)
    Database
      .migrateAndTransactor(s"jdbc:sqlite:${file.toAbsolutePath}")
      .use { xa =>
        val repos = Repositories.fromTransactor(xa)
        seed.transact(xa) *> test(new PeriodService(repos), repos, xa)
      }
      .guarantee(IO.blocking(Files.deleteIfExists(file)).void)
  }

  private def instant(text: String): Instant = Instant.parse(text)

  // ---------------------------------------------------------------------------------------------
  // The reports
  // ---------------------------------------------------------------------------------------------

  private val marchStart = instant("2026-03-01T08:00:00Z")
  private val aprilStart = instant("2026-04-01T08:00:00Z")

  /** A closed period (March) and the open one after it. Three accounts, one of each role, all in the primary currency.
    *
    * In March, by the bank ledger of the spending account: 8,000 of pay came in, 2,000 went to groceries, 300 came back from the broker (the
    * investment account), and 600 went to the savings account as an internal transfer. The savings account grew by 600. The investment went from
    * 10,000 to 9,000: 300 of that was the withdrawal, so the market took 700.
    */
  private val reportSeed: ConnectionIO[Unit] = {
    def account(id: String, role: String, cents: Long, source: String)                        =
      sql"""INSERT INTO accounts (id, name, currency, role, balance_cents, balance_source, balance_updated_at)
            VALUES ($id, $id, 'PLN', $role, $cents, $source, ${instant("2026-04-10T10:00:00Z")})""".update.run
    def balance(id: String, account: String, cents: Long, at: String)                         =
      sql"INSERT INTO balance_snapshots (id, account_id, amount, currency, recorded_at) VALUES ($id, $account, $cents, 'PLN', ${instant(at)})".update.run
    def tx(id: String, cents: Long, day: String, internal: Boolean, category: Option[String]) =
      sql"""INSERT INTO bank_transactions
              (id, connection_id, eb_account_uid, dedup_key, amount_cents, currency, status, booked_at, raw_json, imported_at, is_internal, category_id)
            VALUES ($id, 'conn1', 'uid-spend', $id, $cents, 'PLN', 'booked', ${instant(s"${day}T00:00:00Z")}, '{}', $aprilStart,
                    ${if internal then 1 else 0}, $category)""".update.run
    for {
      _ <- account("spend", "spending", 140000, "bank")
      _ <- account("sav", "savings", 580000, "manual")
      _ <- account("inv", "investment", 950000, "manual")
      // Pay that waits for the next period: 800 at the end of March, 700 now.
      _ <- account("hold", "holding", 70000, "manual")
      _ <- sql"INSERT INTO periods (id, started_at, expected_end, ended_at) VALUES ('march', $marchStart, '2026-04-01', $aprilStart)".update.run
      _ <- sql"INSERT INTO periods (id, started_at, expected_end, ended_at) VALUES ('april', $aprilStart, '2026-05-01', NULL)".update.run
      _ <- sql"""INSERT INTO bank_connections (id, aspsp_name, aspsp_country, status, created_at)
                 VALUES ('conn1', 'Test Bank', 'PL', 'active', $marchStart)""".update.run
      _ <- sql"""INSERT INTO bank_account_links (id, connection_id, eb_account_uid, link_target_kind, link_target_id)
                 VALUES ('link1', 'conn1', 'uid-spend', 'account', 'spend')""".update.run
      _ <- sql"INSERT INTO categories (id, name, color) VALUES ('groc', 'Groceries', '#888')".update.run
      _ <- sql"INSERT INTO categories (id, name, color, transfer_account_id) VALUES ('brk', 'Broker', '#888', 'inv')".update.run
      // Recorded balances at the two boundaries. The one of the investment at the end is recorded at the instant itself: "at or before" has it.
      _ <- balance("b1", "spend", 100000, "2026-03-01T08:00:00Z")
      _ <- balance("b2", "spend", 150000, "2026-04-01T07:00:00Z")
      _ <- balance("b3", "sav", 500000, "2026-03-01T07:00:00Z")
      _ <- balance("b4", "sav", 560000, "2026-03-31T12:00:00Z")
      _ <- balance("b5", "inv", 1000000, "2026-03-01T08:00:00Z")
      _ <- balance("b6", "inv", 900000, "2026-04-01T08:00:00Z")
      _ <- balance("b7", "hold", 80000, "2026-03-30T10:00:00Z")
      _ <- tx("pay", 800000, "2026-03-05", internal = false, None)
      _ <- tx("food", -200000, "2026-03-10", internal = false, Some("groc"))
      _ <- tx("from-broker", 30000, "2026-03-15", internal = false, Some("brk"))
      _ <- tx("to-savings", -60000, "2026-03-20", internal = true, None)
      // April: more groceries, and a deposit of 400 to the broker.
      _ <- tx("food-april", -5000, "2026-04-02", internal = false, Some("groc"))
      _ <- tx("to-broker", -40000, "2026-04-03", internal = false, Some("brk"))
    } yield ()
  }

  "The summary of a closed period" - {
    "has the cash flow of the bank ledger, without transfers" in {
      withDatabase(reportSeed) { (service, _, _) =>
        service.summaries(None).asserting { all =>
          val march = all.find(_.period.id == PeriodId("march")).get
          // The withdrawal from the broker and the transfer to savings are money that moved between own accounts: neither income nor spend.
          march.inflowCents shouldBe 800000L
          march.outflowCents shouldBe 200000L
          march.days shouldBe 31
          march.topCategories.map(c => c.category.name -> c.spentCents) shouldBe List("Groceries" -> 200000L)
        }
      }
    }

    "splits the change of an investment into what was moved and what the market did" in {
      withDatabase(reportSeed) { (service, _, _) =>
        service.summaries(None).asserting { all =>
          val march = all.find(_.period.id == PeriodId("march")).get
          march.savingsChangeCents shouldBe 60000L
          march.savingsEndCents shouldBe Some(560000L)
          march.investmentChangeCents shouldBe -100000L
          // 300 arrived at the bank from the broker: that much was taken out of the investment.
          march.investmentMovedCents shouldBe -30000L
          march.investmentMarketCents shouldBe -70000L
          march.investmentEndCents shouldBe Some(900000L)
          // Saved: the savings accounts grew by 600, and 300 was taken out of the investment.
          march.savedCents shouldBe 30000L
          march.endBalanceCents shouldBe Some(150000L)
          // Money on hold is a balance of its own: it is not in the spending balance, and it is not in the savings figures.
          march.holdingEndCents shouldBe Some(80000L)
        }
      }
    }

    "lists the periods newest first, and the open one ends at the live balances" in {
      withDatabase(reportSeed) { (service, _, _) =>
        service.summaries(None).asserting { all =>
          all.map(_.period.id.value) shouldBe List("april", "march")
          val april = all.head
          april.endBalanceCents shouldBe Some(140000L)
          april.savingsEndCents shouldBe Some(580000L)
          april.savingsChangeCents shouldBe 20000L
          // The investment went from 9,000 to 9,500, and 400 of that was a deposit.
          april.investmentChangeCents shouldBe 50000L
          april.investmentMovedCents shouldBe 40000L
          april.investmentMarketCents shouldBe 10000L
        }
      }
    }
  }

  "The baselines of the open period" - {
    "give the balance at its start for savings and investment accounts, and what was moved for an investment only" in {
      withDatabase(reportSeed) { (service, _, _) =>
        service.baselines.asserting { baselines =>
          baselines.map(b => (b.accountId.value, b.baseline.amountCents, b.movedCents)).toSet shouldBe Set(
            ("sav", 560000L, 0L),
            ("inv", 900000L, 40000L),
          )
        }
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The lifecycle
  // ---------------------------------------------------------------------------------------------

  private val today     = LocalDate.now(ZoneOffset.UTC)
  private val openStart = today.minusDays(10)

  private def at(day: LocalDate, hour: Int): Instant = day.atStartOfDay(ZoneOffset.UTC).plusHours(hour.toLong).toInstant

  /** One open period that started 10 days ago with a plan to save 500, and a daily record for the last three days. */
  private val lifecycleSeed: ConnectionIO[Unit] = {
    def day(d: LocalDate) =
      sql"""INSERT INTO daily_budget_snapshots
              (on_date, captured_at, period_id, currency, spendable_cents, planned_to_pay_cents, planned_to_receive_cents, budgets_to_spend_cents,
               budgets_to_receive_cents, savings_cents, days_remaining, source)
            VALUES ($d, ${at(d, 12)}, 'open', 'PLN', 100000, 0, 0, 0, 0, 0, 99, 'live')""".update.run
    for {
      _ <- sql"""INSERT INTO periods (id, started_at, expected_end, ended_at, planned_saving_cents)
                 VALUES ('open', ${at(openStart, 9)}, ${openStart.plusDays(30)}, NULL, 50000)""".update.run
      _ <- List(3, 2, 1).traverse_(n => day(today.minusDays(n.toLong)))
    } yield ()
  }

  private def recordedPeriods(xa: Transactor[IO]): IO[List[(String, Option[String], Int)]] =
    sql"SELECT on_date, period_id, days_remaining FROM daily_budget_snapshots ORDER BY on_date"
      .query[(String, Option[String], Int)]
      .to[List]
      .transact(xa)

  "Starting a new period" - {
    "closes the open period at the same instant, and carries the planned saving" in {
      withDatabase(lifecycleSeed) { (service, repos, _) =>
        for {
          started <- service.startNew(None)
          all     <- repos.periods.findAll
        } yield {
          val next = started.toOption.get
          all.count(_.endDate.isEmpty) shouldBe 1
          all.find(_.id == PeriodId("open")).flatMap(_.endDate) shouldBe Some(next.startDate)
          next.plannedSavingCents shouldBe 50000L
          next.startDay shouldBe today
        }
      }
    }

    "as of an earlier day, starts at the beginning of that day and takes the days that were recorded since" in {
      withDatabase(lifecycleSeed) { (service, repos, xa) =>
        val day = today.minusDays(2)
        for {
          started <- service.startNew(Some(day))
          all     <- repos.periods.findAll
          rows    <- recordedPeriods(xa)
        } yield {
          val next = started.toOption.get
          next.startDate shouldBe at(day, 0)
          all.find(_.id == PeriodId("open")).flatMap(_.endDate) shouldBe Some(at(day, 0))
          // The day before stays with the old period. The two days from the start on are of the new one, counted to its expected end.
          rows shouldBe List(
            (today.minusDays(3).toString, Some("open"), 99),
            (today.minusDays(2).toString, Some(next.id.value), next.daysRemaining(today.minusDays(2))),
            (today.minusDays(1).toString, Some(next.id.value), next.daysRemaining(today.minusDays(1))),
          )
        }
      }
    }

    "refuses a day in the future, and a day that is not after the start of the open period" in {
      withDatabase(lifecycleSeed) { (service, repos, _) =>
        for {
          future <- service.startNew(Some(today.plusDays(1)))
          early  <- service.startNew(Some(openStart))
          all    <- repos.periods.findAll
        } yield {
          future.isLeft shouldBe true
          early.isLeft shouldBe true
          all.map(_.id.value) shouldBe List("open")
        }
      }
    }

    "works with no period yet" in {
      withDatabase(().pure[ConnectionIO]) { (service, repos, _) =>
        for {
          started <- service.startNew(None)
          all     <- repos.periods.findAll
        } yield {
          started.isRight shouldBe true
          all.map(_.endDate) shouldBe List(None)
        }
      }
    }
  }

  "The database" - {
    "does not accept a second open period" in {
      withDatabase(lifecycleSeed) { (_, _, xa) =>
        sql"INSERT INTO periods (id, started_at, expected_end, ended_at) VALUES ('second', ${at(today, 1)}, ${today.plusDays(30)}, NULL)".update.run
          .transact(xa)
          .attempt
          .asserting(_.isLeft shouldBe true)
      }
    }
  }

  "The instant a period starts at" - {
    val now  = instant("2026-11-19T14:30:00Z")
    val open = Period(PeriodId("p"), instant("2026-11-01T09:00:00Z"), LocalDate.parse("2026-12-01"), None, 0L)

    "is now, with no day or with today" in {
      PeriodService.startInstant(None, now, Some(open)) shouldBe Right(now)
      PeriodService.startInstant(Some(LocalDate.parse("2026-11-19")), now, Some(open)) shouldBe Right(now)
    }

    "is the beginning of an earlier day" in {
      PeriodService.startInstant(Some(LocalDate.parse("2026-11-17")), now, Some(open)) shouldBe Right(instant("2026-11-17T00:00:00Z"))
      PeriodService.startInstant(Some(LocalDate.parse("2026-11-17")), now, None) shouldBe Right(instant("2026-11-17T00:00:00Z"))
    }

    "is refused for tomorrow and for the day the open period started" in {
      PeriodService.startInstant(Some(LocalDate.parse("2026-11-20")), now, Some(open)).isLeft shouldBe true
      PeriodService.startInstant(Some(LocalDate.parse("2026-11-01")), now, Some(open)).isLeft shouldBe true
      PeriodService.startInstant(Some(LocalDate.parse("2026-11-02")), now, Some(open)).isRight shouldBe true
    }
  }
}
