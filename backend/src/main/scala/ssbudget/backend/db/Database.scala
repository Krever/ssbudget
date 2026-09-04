package ssbudget.backend.db

import cats.effect.{IO, Resource}
import com.zaxxer.hikari.HikariConfig
import doobie.*
import doobie.implicits.*
import doobie.hikari.HikariTransactor
import doobie.util.ExecutionContexts
import org.flywaydb.core.Flyway

object Database {

  def migrateAndTransactor(jdbcUrl: String): Resource[IO, HikariTransactor[IO]] = {
    for {
      ce <- ExecutionContexts.fixedThreadPool[IO](32)
      xa <- HikariTransactor.fromHikariConfigCustomEc[IO](hikariConfig(jdbcUrl), ce)
      // WAL lets a second reader — the bundled Metabase — query the file while the app writes to it,
      // without either blocking the other. The setting lives in the database header, so this is a
      // no-op after the first run.
      _  <- Resource.eval(enableWal(xa))
      // Run migrations using the HikariCP data source to ensure
      // in-memory databases (with shared cache) keep their state
      _  <- Resource.eval(migrateWithDataSource(xa))
    } yield xa
  }

  /** One connection, on purpose. SQLite allows a single writer at a time, so a pool of several connections buys no write throughput — it only lets
    * two of our own transactions collide. That is what a bank sync kept hitting: the import fiber writes transactions and job progress while the UI
    * polls the job, and every authenticated request writes `sessions.last_used_at`. Doobie opens deferred transactions, so a transaction that reads
    * and then writes has to upgrade its lock, and if another connection committed in between SQLite fails it with `SQLITE_BUSY: database is locked`
    * *immediately* — the busy handler deliberately doesn't retry that case, which is why waiting longer never helped. A single connection serialises
    * all of the app's database work instead, and costs nothing here: the transactions are tiny and there is one user.
    *
    * `busy_timeout` (3s by default in the driver) only covers plain lock contention with a writer outside this pool, so it is raised as a cushion,
    * not as the fix.
    */
  private def hikariConfig(jdbcUrl: String): HikariConfig = {
    val config = new HikariConfig()
    config.setDriverClassName("org.sqlite.JDBC")
    config.setJdbcUrl(jdbcUrl)
    config.setMaximumPoolSize(1)
    config.setConnectionInitSql("PRAGMA busy_timeout = 10000")
    config
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
