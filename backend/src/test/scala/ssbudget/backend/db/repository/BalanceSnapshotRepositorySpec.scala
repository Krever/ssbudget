package ssbudget.backend.db.repository

import cats.effect.IO
import cats.syntax.all.*
import ssbudget.shared.model.*

import java.time.Instant

class BalanceSnapshotRepositorySpec extends RepositorySpec {

  private def setupAccount(accountRepo: AccountRepository): IO[Unit] = {
    val account = spendingAccount("acc-1", "Main", Currency.PLN)
    accountRepo.create(account)
  }

  "create and findById returns the balance snapshot" in {
    val accountRepo  = new AccountRepositoryImpl(xa)
    val snapshotRepo = new BalanceSnapshotRepositoryImpl(xa)
    val snapshot     = BalanceSnapshot(
      BalanceSnapshotId("snap-1"),
      AccountId("acc-1"),
      500000L,
      Currency.PLN,
      Instant.parse("2024-01-15T10:00:00Z"),
    )

    for {
      _     <- setupAccount(accountRepo)
      _     <- snapshotRepo.create(snapshot)
      found <- snapshotRepo.findById(BalanceSnapshotId("snap-1"))
    } yield found shouldBe Some(snapshot)
  }

  "findById returns None for non-existent snapshot" in {
    val snapshotRepo = new BalanceSnapshotRepositoryImpl(xa)

    for {
      found <- snapshotRepo.findById(BalanceSnapshotId("non-existent"))
    } yield found shouldBe None
  }

  "findByAccount returns all snapshots for that account ordered by time desc" in {
    val accountRepo  = new AccountRepositoryImpl(xa)
    val snapshotRepo = new BalanceSnapshotRepositoryImpl(xa)
    val snap1        = BalanceSnapshot(BalanceSnapshotId("snap-1"), AccountId("acc-1"), 100L, Currency.PLN, Instant.parse("2024-01-10T10:00:00Z"))
    val snap2        = BalanceSnapshot(BalanceSnapshotId("snap-2"), AccountId("acc-1"), 200L, Currency.PLN, Instant.parse("2024-01-15T10:00:00Z"))
    val snap3        = BalanceSnapshot(BalanceSnapshotId("snap-3"), AccountId("acc-1"), 300L, Currency.PLN, Instant.parse("2024-01-12T10:00:00Z"))

    for {
      _   <- setupAccount(accountRepo)
      _   <- snapshotRepo.create(snap1)
      _   <- snapshotRepo.create(snap2)
      _   <- snapshotRepo.create(snap3)
      all <- snapshotRepo.findByAccount(AccountId("acc-1"))
    } yield all shouldBe List(snap2, snap3, snap1)
  }

  "delete removes snapshot" in {
    val accountRepo  = new AccountRepositoryImpl(xa)
    val snapshotRepo = new BalanceSnapshotRepositoryImpl(xa)
    val snapshot     = BalanceSnapshot(BalanceSnapshotId("snap-1"), AccountId("acc-1"), 500000L, Currency.PLN, Instant.parse("2024-01-15T10:00:00Z"))

    for {
      _     <- setupAccount(accountRepo)
      _     <- snapshotRepo.create(snapshot)
      _     <- snapshotRepo.delete(BalanceSnapshotId("snap-1"))
      found <- snapshotRepo.findById(BalanceSnapshotId("snap-1"))
    } yield found shouldBe None
  }

  "findFrom gives the records from a moment on, and for each account the one record that was in force at that moment" in {
    val accountRepo                                                = new AccountRepositoryImpl(xa)
    val snapshotRepo                                               = new BalanceSnapshotRepositoryImpl(xa)
    def snap(id: String, account: String, cents: Long, at: String) =
      BalanceSnapshot(BalanceSnapshotId(id), AccountId(account), cents, Currency.PLN, Instant.parse(at))
    for {
      _    <- accountRepo.create(spendingAccount("acc-1", "Main", Currency.PLN))
      _    <- accountRepo.create(spendingAccount("acc-2", "Old", Currency.PLN))
      _    <- accountRepo.create(spendingAccount("acc-3", "New", Currency.PLN))
      // acc-1: two old records and two new ones. acc-2: only old records. acc-3: only a record after the moment.
      _    <- List(
                snap("a1", "acc-1", 100, "2026-01-05T10:00:00.000Z"),
                snap("a2", "acc-1", 200, "2026-02-20T10:00:00.000Z"),
                snap("a3", "acc-1", 300, "2026-03-01T00:00:00.000Z"),
                snap("a4", "acc-1", 400, "2026-03-10T10:00:00.000Z"),
                snap("b1", "acc-2", 10, "2026-01-01T10:00:00.000Z"),
                snap("b2", "acc-2", 20, "2026-02-01T10:00:00.000Z"),
                snap("c1", "acc-3", 5, "2026-03-05T10:00:00.000Z"),
              ).traverse_(snapshotRepo.create)
      from <- snapshotRepo.findFrom(Instant.parse("2026-03-01T00:00:00.000Z"))
      all  <- snapshotRepo.findAll
    } yield {
      // Oldest first. Of the records before the moment only the last of each account: a2 and b2, never a1 or b1.
      from.map(_.id.value) shouldBe List("b2", "a2", "a3", "c1", "a4")
      all.map(_.id.value) shouldBe List("b1", "a1", "b2", "a2", "a3", "c1", "a4")
    }
  }
}
