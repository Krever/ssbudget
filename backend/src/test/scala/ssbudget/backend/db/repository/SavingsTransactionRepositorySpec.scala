package ssbudget.backend.db.repository

import cats.effect.unsafe.implicits.global
import ssbudget.shared.model.*

import java.time.Instant

class SavingsTransactionRepositorySpec extends RepositorySpec {

  private val now       = Instant.parse("2026-01-15T10:00:00Z")
  private val yesterday = Instant.parse("2026-01-14T10:00:00Z")

  private def createPrerequisites(): Unit = {
    // Create savings account first (foreign key)
    val accountRepo = new AccountRepositoryImpl(xa)
    val periodRepo  = new PeriodRepositoryImpl(xa)

    (for {
      _ <- accountRepo.create(savingsAccount("sav-1", "Fund 1", Currency.PLN, 0, None))
      _ <- accountRepo.create(savingsAccount("sav-2", "Fund 2", Currency.EUR, 0, None))
      _ <- periodRepo.create(Period(PeriodId("period-1"), now, None))
      _ <- periodRepo.create(Period(PeriodId("period-2"), yesterday, Some(now)))
    } yield ()).unsafeRunSync()
  }

  "create and findById returns the transaction" in {
    createPrerequisites()
    val repo = new SavingsTransactionRepositoryImpl(xa)
    val txn  = SavingsTransaction(
      SavingsTransactionId("txn-1"),
      AccountId("sav-1"),
      PeriodId("period-1"),
      50000,
      Some("Initial deposit"),
      now,
    )

    for {
      _     <- repo.create(txn)
      found <- repo.findById(SavingsTransactionId("txn-1"))
    } yield found shouldBe Some(txn)
  }

  "findById returns None for non-existent transaction" in {
    createPrerequisites()
    val repo = new SavingsTransactionRepositoryImpl(xa)

    for {
      found <- repo.findById(SavingsTransactionId("non-existent"))
    } yield found shouldBe None
  }

  "findByAccountId returns transactions for account ordered by created_at desc" in {
    createPrerequisites()
    val repo = new SavingsTransactionRepositoryImpl(xa)
    val txn1 = SavingsTransaction(SavingsTransactionId("txn-1"), AccountId("sav-1"), PeriodId("period-1"), 50000, None, yesterday)
    val txn2 = SavingsTransaction(SavingsTransactionId("txn-2"), AccountId("sav-1"), PeriodId("period-1"), -10000, Some("Withdrawal"), now)
    val txn3 = SavingsTransaction(SavingsTransactionId("txn-3"), AccountId("sav-2"), PeriodId("period-1"), 25000, None, now)

    for {
      _      <- repo.create(txn1)
      _      <- repo.create(txn2)
      _      <- repo.create(txn3)
      result <- repo.findByAccountId(AccountId("sav-1"))
    } yield result shouldBe List(txn2, txn1) // Newest first
  }

  "findByPeriodId returns transactions for period" in {
    createPrerequisites()
    val repo = new SavingsTransactionRepositoryImpl(xa)
    val txn1 = SavingsTransaction(SavingsTransactionId("txn-1"), AccountId("sav-1"), PeriodId("period-1"), 50000, None, now)
    val txn2 = SavingsTransaction(SavingsTransactionId("txn-2"), AccountId("sav-1"), PeriodId("period-2"), 30000, None, yesterday)

    for {
      _      <- repo.create(txn1)
      _      <- repo.create(txn2)
      result <- repo.findByPeriodId(PeriodId("period-1"))
    } yield result shouldBe List(txn1)
  }

  "findByAccountAndPeriod returns matching transactions" in {
    createPrerequisites()
    val repo = new SavingsTransactionRepositoryImpl(xa)
    val txn1 = SavingsTransaction(SavingsTransactionId("txn-1"), AccountId("sav-1"), PeriodId("period-1"), 50000, None, yesterday)
    val txn2 = SavingsTransaction(SavingsTransactionId("txn-2"), AccountId("sav-1"), PeriodId("period-1"), -10000, None, now)
    val txn3 = SavingsTransaction(SavingsTransactionId("txn-3"), AccountId("sav-1"), PeriodId("period-2"), 30000, None, now)
    val txn4 = SavingsTransaction(SavingsTransactionId("txn-4"), AccountId("sav-2"), PeriodId("period-1"), 25000, None, now)

    for {
      _      <- repo.create(txn1)
      _      <- repo.create(txn2)
      _      <- repo.create(txn3)
      _      <- repo.create(txn4)
      result <- repo.findByAccountAndPeriod(AccountId("sav-1"), PeriodId("period-1"))
    } yield result shouldBe List(txn2, txn1) // Newest first
  }

  "update modifies transaction" in {
    createPrerequisites()
    val repo    = new SavingsTransactionRepositoryImpl(xa)
    val txn     = SavingsTransaction(SavingsTransactionId("txn-1"), AccountId("sav-1"), PeriodId("period-1"), 50000, None, now)
    val updated = txn.copy(amount = 60000, note = Some("Updated note"))

    for {
      _     <- repo.create(txn)
      _     <- repo.update(updated)
      found <- repo.findById(SavingsTransactionId("txn-1"))
    } yield found shouldBe Some(updated)
  }

  "delete removes transaction" in {
    createPrerequisites()
    val repo = new SavingsTransactionRepositoryImpl(xa)
    val txn  = SavingsTransaction(SavingsTransactionId("txn-1"), AccountId("sav-1"), PeriodId("period-1"), 50000, None, now)

    for {
      _     <- repo.create(txn)
      _     <- repo.delete(SavingsTransactionId("txn-1"))
      found <- repo.findById(SavingsTransactionId("txn-1"))
    } yield found shouldBe None
  }

  "deleteByAccountId removes all transactions for account" in {
    createPrerequisites()
    val repo = new SavingsTransactionRepositoryImpl(xa)
    val txn1 = SavingsTransaction(SavingsTransactionId("txn-1"), AccountId("sav-1"), PeriodId("period-1"), 50000, None, now)
    val txn2 = SavingsTransaction(SavingsTransactionId("txn-2"), AccountId("sav-1"), PeriodId("period-2"), 30000, None, yesterday)
    val txn3 = SavingsTransaction(SavingsTransactionId("txn-3"), AccountId("sav-2"), PeriodId("period-1"), 25000, None, now)

    for {
      _       <- repo.create(txn1)
      _       <- repo.create(txn2)
      _       <- repo.create(txn3)
      _       <- repo.deleteByAccountId(AccountId("sav-1"))
      acc1Txn <- repo.findByAccountId(AccountId("sav-1"))
      acc2Txn <- repo.findByAccountId(AccountId("sav-2"))
    } yield {
      acc1Txn shouldBe empty
      acc2Txn shouldBe List(txn3)
    }
  }

  "handles negative amounts (outflows)" in {
    createPrerequisites()
    val repo = new SavingsTransactionRepositoryImpl(xa)
    val txn  =
      SavingsTransaction(SavingsTransactionId("txn-1"), AccountId("sav-1"), PeriodId("period-1"), -25000, Some("Emergency withdrawal"), now)

    for {
      _     <- repo.create(txn)
      found <- repo.findById(SavingsTransactionId("txn-1"))
    } yield {
      found.map(_.amount) shouldBe Some(-25000)
      found.flatMap(_.note) shouldBe Some("Emergency withdrawal")
    }
  }
}
