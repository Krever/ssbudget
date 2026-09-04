package ssbudget.backend.db

import cats.effect.{IO, Resource}
import doobie.*
import doobie.implicits.*
import doobie.hikari.HikariTransactor
import doobie.util.ExecutionContexts
import org.flywaydb.core.Flyway

object Database {

  def migrateAndTransactor(jdbcUrl: String): Resource[IO, HikariTransactor[IO]] = {
    for {
      ce <- ExecutionContexts.fixedThreadPool[IO](32)
      xa <- HikariTransactor.newHikariTransactor[IO](
              "org.sqlite.JDBC",
              jdbcUrl,
              "", // no username for SQLite
              "", // no password for SQLite
              ce,
            )
      // WAL lets a second reader — the bundled Metabase — query the file while the app writes to it,
      // without either blocking the other. The setting lives in the database header, so this is a
      // no-op after the first run.
      _  <- Resource.eval(enableWal(xa))
      // Run migrations using the HikariCP data source to ensure
      // in-memory databases (with shared cache) keep their state
      _  <- Resource.eval(migrateWithDataSource(xa))
    } yield xa
  }

  /** Puts the database in WAL mode, which is what lets a bundled Metabase read the file while the app writes to it.
    *
    * It has to run on a raw connection: `PRAGMA journal_mode=WAL` cannot execute inside a transaction, and doobie's `transact` always opens one. The
    * setting lives in the database header, so one successful call is permanent — but in-memory databases (used by tests) simply don't support WAL, so
    * a failure is reported rather than fatal.
    */
  private def enableWal(xa: HikariTransactor[IO]): IO[Unit] =
    xa.configure { ds =>
      IO.blocking {
        val connection = ds.getConnection
        try {
          val rs = connection.createStatement().executeQuery("PRAGMA journal_mode=WAL")
          try if rs.next() then rs.getString(1) else "unknown"
          finally rs.close()
        } finally connection.close()
      }
    }.attempt
      .flatMap {
        case Right("wal") => IO.unit
        case Right(mode)  => IO.println(s"SQLite journal mode is '$mode', not WAL — the app and Metabase may contend for the database file")
        case Left(e)      => IO.println(s"Could not enable SQLite WAL mode (expected for in-memory databases): ${e.getMessage}")
      }

  private def migrateWithDataSource(xa: HikariTransactor[IO]): IO[Unit] = IO.blocking {
    val hikariDataSource = xa.kernel
    Flyway
      .configure()
      .dataSource(hikariDataSource)
      .load()
      .migrate()
    ()
  }
}
