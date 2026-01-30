package ssbudget.backend

import cats.effect.{IO, IOApp, Resource}
import cats.implicits.*
import com.comcast.ip4s.Port

import ssbudget.backend.db.{Database, Repositories}

import java.nio.file.{Files, Paths}

object Main extends IOApp.Simple {

  private val dbPath     = sys.env.getOrElse("SSBUDGET_DB_PATH", "data/ssbudget.db")
  private val jdbcUrl    = s"jdbc:sqlite:$dbPath"
  private val testMode   = sys.env.contains("SSBUDGET_TEST_MODE")
  private val serverPort = Port.fromString(sys.env.getOrElse("SSBUDGET_PORT", "8080")).getOrElse(Port.fromInt(8080).get)

  private def ensureDbDirectoryExists: IO[Unit] = IO.blocking {
    val path = Paths.get(dbPath).getParent
    if path != null && !Files.exists(path) then {
      Files.createDirectories(path)
    }
  }

  override def run: IO[Unit] = {
    val resources = for {
      _    <- Resource.eval(IO.println(s"Using database: $jdbcUrl"))
      _    <- Resource.eval(ensureDbDirectoryExists)
      xa   <- Database.migrateAndTransactor(jdbcUrl)
      repos = Repositories.fromTransactor(xa)
      _    <- Resource.eval(IO.println("Database migrated successfully"))
      s    <- ServerBuilder.build(repos, xa, serverPort, testMode, dbPath)
    } yield s

    resources.use { s =>
      IO.println(s"Server started at http://localhost:${s.address.getPort}") *>
        IO.never
    }
  }
}
