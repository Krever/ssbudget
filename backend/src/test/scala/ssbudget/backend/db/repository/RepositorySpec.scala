package ssbudget.backend.db.repository

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.unsafe.implicits.global
import doobie.Transactor
import org.scalatest.BeforeAndAfterEach
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers
import ssbudget.backend.db.Database
import ssbudget.shared.model.*

import java.time.Instant
import java.util.UUID

trait RepositorySpec extends AsyncFreeSpec with AsyncIOSpec with Matchers with BeforeAndAfterEach {

  protected var xa: Transactor[IO] = scala.compiletime.uninitialized
  private var cleanup: IO[Unit]    = IO.unit

  private val fixedAt = Instant.parse("2024-01-01T00:00:00Z")

  protected def spendingAccount(id: String, name: String, currency: Currency, cents: Long = 0L): Account =
    Account(AccountId(id), name, currency, AccountRole.Spending, cents, None, BalanceSource.Manual, Some(fixedAt))

  protected def savingsAccount(id: String, name: String, currency: Currency, cents: Long, target: Option[Long]): Account =
    Account(AccountId(id), name, currency, AccountRole.Savings, cents, target, BalanceSource.Manual, Some(fixedAt))

  override def beforeEach(): Unit = {
    // Use shared-cache mode with a unique name so each test gets its own isolated database
    // that persists across multiple connections during the test
    val dbName                = UUID.randomUUID().toString
    val jdbcUrl               = s"jdbc:sqlite:file:$dbName?mode=memory&cache=shared"
    val resource              = Database.migrateAndTransactor(jdbcUrl)
    val (transactor, release) = resource.allocated.unsafeRunSync()
    xa = transactor
    cleanup = release
  }

  override def afterEach(): Unit = {
    cleanup.unsafeRunSync()
  }
}
