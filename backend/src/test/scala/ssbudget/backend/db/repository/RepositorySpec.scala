package ssbudget.backend.db.repository

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.unsafe.implicits.global
import doobie.Transactor
import org.scalatest.BeforeAndAfterEach
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers
import ssbudget.backend.db.Database

import java.util.UUID

trait RepositorySpec extends AsyncFreeSpec with AsyncIOSpec with Matchers with BeforeAndAfterEach {

  protected var xa: Transactor[IO] = scala.compiletime.uninitialized
  private var cleanup: IO[Unit]    = IO.unit

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
