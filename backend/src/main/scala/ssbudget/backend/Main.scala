package ssbudget.backend

import cats.effect.{IO, IOApp, Resource}
import cats.implicits.*
import com.comcast.ip4s.Port

import ssbudget.backend.db.{Database, Repositories}

import java.nio.file.{Files, Paths}

object Main extends IOApp.Simple {

  private val dbPath     = sys.env.getOrElse("SSBUDGET_DB_PATH", "data/ssbudget.db")
  private val jdbcUrl    = s"jdbc:sqlite:$dbPath"
  // Test mode turns the login off. Only an explicit "1" or "true" turns it on: a variable that is present with "false" must not.
  private val testMode   = sys.env.get("SSBUDGET_TEST_MODE").map(_.trim.toLowerCase).exists(Set("1", "true"))
  private val rpId       = sys.env.getOrElse("SSBUDGET_RP_ID", "localhost")
  private val serverPort = Port.fromString(sys.env.getOrElse("SSBUDGET_PORT", "8080")).getOrElse(Port.fromInt(8080).get)

  private def ensureDbDirectoryExists: IO[Unit] = IO.blocking {
    val path = Paths.get(dbPath).getParent
    if path != null && !Files.exists(path) then {
      val _ = Files.createDirectories(path)
    }
  }

  override def run: IO[Unit] = {
    // An instance that is configured for a real host is on the internet, and there test mode would give the data to everyone.
    val refuseTestMode = IO.raiseWhen(testMode && rpId != "localhost")(
      new IllegalStateException(s"SSBUDGET_TEST_MODE is on, and SSBUDGET_RP_ID is '$rpId'. Test mode has no login and runs only on localhost."),
    )
    val warnTestMode   = IO.whenA(testMode)(IO.println("WARNING: SSBUDGET_TEST_MODE is on. There is NO LOGIN: all data is open to every caller."))

    val resources = for {
      _    <- Resource.eval(refuseTestMode *> warnTestMode)
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
