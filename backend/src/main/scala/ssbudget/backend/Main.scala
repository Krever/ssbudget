package ssbudget.backend

import cats.effect.{IO, IOApp, Resource}
import com.comcast.ip4s.{host, port}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Server
import sttp.tapir.server.http4s.Http4sServerInterpreter

import ssbudget.shared.api.HealthEndpoint

object Main extends IOApp.Simple {

  private val healthRoute = Http4sServerInterpreter[IO]().toRoutes(
    HealthEndpoint.health.serverLogicSuccess(_ => IO.pure("ok")),
  )

  private val server: Resource[IO, Server] =
    EmberServerBuilder
      .default[IO]
      .withHost(host"0.0.0.0")
      .withPort(port"8080")
      .withHttpApp(healthRoute.orNotFound)
      .build

  override def run: IO[Unit] =
    server.use { s =>
      IO.println(s"Server started at http://localhost:${s.address.getPort}") *>
        IO.never
    }
}
