package ssbudget.backend.db

import cats.effect.{IO, Resource}
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
      // Run migrations using the HikariCP data source to ensure
      // in-memory databases (with shared cache) keep their state
      _  <- Resource.eval(migrateWithDataSource(xa))
    } yield xa
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
