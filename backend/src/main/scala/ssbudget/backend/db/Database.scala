package ssbudget.backend.db

import cats.effect.{IO, Resource}
import com.zaxxer.hikari.HikariConfig
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
    // SQLite checks a REFERENCES only when this is on, and it is a setting of the connection, not of the file. The driver sets it when it opens
    // a connection. With it on, a row cannot name a parent that is not there, and a parent with children cannot be deleted: the code deletes
    // the children first, or the schema has no reference (see V33).
    config.addDataSourceProperty("foreign_keys", "true")
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

  /** Bring the database of `xa` up to the schema of this build. Run at start, and after a database file is restored over the live one. */
  def migrate(xa: HikariTransactor[IO]): IO[Unit] = migrateWithDataSource(xa)

  /** The schema versions that this build knows, as Flyway writes them into `flyway_schema_history.version`. */
  def knownVersions(xa: HikariTransactor[IO]): IO[Set[String]] = IO.blocking {
    Flyway.configure().dataSource(xa.kernel).load().info().all().toList.flatMap(i => Option(i.getVersion).map(_.getVersion)).toSet
  }

  /** Runs the migrations with the foreign keys OFF, and turns them on again after.
    *
    * A migration that makes a table again (SQLite has no other way to change a column or a constraint) drops the old table. With the keys on, that is
    * a delete of every row, and it fails when another table refers to them. SQLite says to do such a change with the keys off. The setting cannot
    * change inside a transaction, so it is set on the connection before Flyway starts one. The pool has one connection, so the connection that Flyway
    * gets is the one that the two statements here get.
    */
  private def migrateWithDataSource(xa: HikariTransactor[IO]): IO[Unit] = IO.blocking {
    val hikariDataSource               = xa.kernel
    def foreignKeys(on: Boolean): Unit = {
      val connection = hikariDataSource.getConnection
      try {
        val statement = connection.createStatement()
        try { val _ = statement.execute(s"PRAGMA foreign_keys = ${if on then "ON" else "OFF"}") }
        finally statement.close()
      } finally connection.close()
    }
    foreignKeys(on = false)
    try {
      val _ = Flyway.configure().dataSource(hikariDataSource).load().migrate()
      restoreMissingViews(hikariDataSource)
    } finally foreignKeys(on = true)
  }

  private val viewsScript = "db/migration/R__analytics_views.sql"

  /** Makes the reporting views again when one of them is not there.
    *
    * The views are derived from the tables, and Flyway makes them from one repeatable script, which it runs again only when the text of the script
    * changes. A migration that makes a table again must drop the views that read it. If the script was not changed with that migration, Flyway would
    * leave the views dropped, and the Analytics page would fail with no sign at start. So the views that the script names are checked here after each
    * migrate, and the script is run when one is missing. It drops each view before it makes it, so a second run is safe.
    */
  private def restoreMissingViews(dataSource: javax.sql.DataSource): Unit = {
    val script     = scala.io.Source.fromResource(viewsScript, getClass.getClassLoader)
    val text       =
      try script.getLines().filterNot(_.trim.startsWith("--")).mkString("\n")
      finally script.close()
    val expected   = "(?i)CREATE\\s+VIEW\\s+(\\w+)".r.findAllMatchIn(text).map(_.group(1)).toSet
    val connection = dataSource.getConnection
    try {
      val statement = connection.createStatement()
      try {
        val rows    = statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'view'")
        val present = Iterator.continually(rows).takeWhile(_.next()).map(_.getString(1)).toSet
        rows.close()
        if !expected.subsetOf(present) then {
          // The driver runs one statement in a call.
          text.split(";").map(_.trim).filter(_.nonEmpty).foreach { sql =>
            val _ = statement.execute(sql)
          }
        }
      } finally statement.close()
    } finally connection.close()
  }
}
