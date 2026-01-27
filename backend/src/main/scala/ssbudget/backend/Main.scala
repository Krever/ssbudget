package ssbudget.backend

import cats.effect.{IO, IOApp, Resource}
import com.comcast.ip4s.{host, port}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Server
import sttp.tapir.server.http4s.Http4sServerInterpreter

import ssbudget.backend.db.{Database, Repositories}
import ssbudget.shared.api.HealthEndpoint

import java.nio.file.{Files, Paths}

object Main extends IOApp.Simple {

  private val dbPath  = sys.env.getOrElse("SSBUDGET_DB_PATH", "data/ssbudget.db")
  private val jdbcUrl = s"jdbc:sqlite:$dbPath"

  private def ensureDbDirectoryExists: IO[Unit] = IO.blocking {
    val path = Paths.get(dbPath).getParent
    if path != null && !Files.exists(path) then {
      Files.createDirectories(path)
    }
  }

  private val healthRoute = Http4sServerInterpreter[IO]().toRoutes(
    HealthEndpoint.health.serverLogicSuccess(_ => IO.pure("ok")),
  )

  private def server(repos: Repositories): Resource[IO, Server] =
    EmberServerBuilder
      .default[IO]
      .withHost(host"0.0.0.0")
      .withPort(port"8080")
      .withHttpApp(healthRoute.orNotFound)
      .build

  override def run: IO[Unit] = {
    val resources = for {
      _    <- Resource.eval(IO.println(s"Using database: $jdbcUrl"))
      _    <- Resource.eval(ensureDbDirectoryExists)
      xa   <- Database.migrateAndTransactor(jdbcUrl)
      repos = Repositories.fromTransactor(xa)
      _    <- Resource.eval(IO.println("Database migrated successfully"))
      s    <- server(repos)
    } yield s

    resources.use { s =>
      IO.println(s"Server started at http://localhost:${s.address.getPort}") *>
        IO.never
    }
  }
}
