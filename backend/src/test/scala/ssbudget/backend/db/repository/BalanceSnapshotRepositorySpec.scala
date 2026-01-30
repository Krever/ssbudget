package ssbudget.backend.db.repository

import cats.effect.IO
import ssbudget.shared.model.*

import java.time.Instant

class BalanceSnapshotRepositorySpec extends RepositorySpec {

  private def setupAccount(accountRepo: AccountRepository): IO[Unit] = {
    val account = Account(AccountId("acc-1"), "Main", Currency.PLN)
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

  "findLatestByAccount returns most recent snapshot" in {
    val accountRepo  = new AccountRepositoryImpl(xa)
    val snapshotRepo = new BalanceSnapshotRepositoryImpl(xa)
    val snap1        = BalanceSnapshot(BalanceSnapshotId("snap-1"), AccountId("acc-1"), 100L, Currency.PLN, Instant.parse("2024-01-10T10:00:00Z"))
    val snap2        = BalanceSnapshot(BalanceSnapshotId("snap-2"), AccountId("acc-1"), 200L, Currency.PLN, Instant.parse("2024-01-15T10:00:00Z"))

    for {
      _      <- setupAccount(accountRepo)
      _      <- snapshotRepo.create(snap1)
      _      <- snapshotRepo.create(snap2)
      latest <- snapshotRepo.findLatestByAccount(AccountId("acc-1"))
    } yield latest shouldBe Some(snap2)
  }

  "findAllLatest returns one snapshot per account" in {
    val accountRepo  = new AccountRepositoryImpl(xa)
    val snapshotRepo = new BalanceSnapshotRepositoryImpl(xa)

    val acc1 = Account(AccountId("acc-1"), "Main", Currency.PLN)
    val acc2 = Account(AccountId("acc-2"), "Savings", Currency.EUR)

    val snap1a = BalanceSnapshot(BalanceSnapshotId("snap-1a"), AccountId("acc-1"), 100L, Currency.PLN, Instant.parse("2024-01-10T10:00:00Z"))
    val snap1b = BalanceSnapshot(BalanceSnapshotId("snap-1b"), AccountId("acc-1"), 200L, Currency.PLN, Instant.parse("2024-01-15T10:00:00Z"))
    val snap2a = BalanceSnapshot(BalanceSnapshotId("snap-2a"), AccountId("acc-2"), 300L, Currency.EUR, Instant.parse("2024-01-12T10:00:00Z"))

    for {
      _      <- accountRepo.create(acc1)
      _      <- accountRepo.create(acc2)
      _      <- snapshotRepo.create(snap1a)
      _      <- snapshotRepo.create(snap1b)
      _      <- snapshotRepo.create(snap2a)
      latest <- snapshotRepo.findAllLatest
    } yield {
      latest.length shouldBe 2
      latest should contain(snap1b)
      latest should contain(snap2a)
    }
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

  "deleteByAccountId removes all snapshots for that account" in {
    val accountRepo  = new AccountRepositoryImpl(xa)
    val snapshotRepo = new BalanceSnapshotRepositoryImpl(xa)

    val acc1 = Account(AccountId("acc-1"), "Main", Currency.PLN)
    val acc2 = Account(AccountId("acc-2"), "Savings", Currency.EUR)

    val snap1a = BalanceSnapshot(BalanceSnapshotId("snap-1a"), AccountId("acc-1"), 100L, Currency.PLN, Instant.parse("2024-01-10T10:00:00Z"))
    val snap1b = BalanceSnapshot(BalanceSnapshotId("snap-1b"), AccountId("acc-1"), 200L, Currency.PLN, Instant.parse("2024-01-15T10:00:00Z"))
    val snap2a = BalanceSnapshot(BalanceSnapshotId("snap-2a"), AccountId("acc-2"), 300L, Currency.EUR, Instant.parse("2024-01-12T10:00:00Z"))

    for {
      _         <- accountRepo.create(acc1)
      _         <- accountRepo.create(acc2)
      _         <- snapshotRepo.create(snap1a)
      _         <- snapshotRepo.create(snap1b)
      _         <- snapshotRepo.create(snap2a)
      _         <- snapshotRepo.deleteByAccountId(AccountId("acc-1"))
      acc1Snaps <- snapshotRepo.findByAccount(AccountId("acc-1"))
      acc2Snaps <- snapshotRepo.findByAccount(AccountId("acc-2"))
    } yield {
      acc1Snaps shouldBe empty
      acc2Snaps shouldBe List(snap2a)
    }
  }
}
