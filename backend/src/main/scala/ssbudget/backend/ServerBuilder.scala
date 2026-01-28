package ssbudget.backend

import cats.effect.{IO, Resource}
import cats.implicits.*
import com.comcast.ip4s.{Host, Port, host}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Server
import sttp.tapir.server.http4s.Http4sServerInterpreter

import ssbudget.backend.db.Repositories
import ssbudget.shared.api.HealthEndpoint

/** Reusable server builder for production and testing */
object ServerBuilder {

  private val healthRoute = Http4sServerInterpreter[IO]().toRoutes(
    HealthEndpoint.health.serverLogicSuccess(_ => IO.pure("ok")),
  )

  /** Build a server resource with the given configuration */
  def build(
      repos: Repositories,
      port: Port,
      testMode: Boolean = false,
  ): Resource[IO, Server] = {
    val allRoutes = healthRoute <+> Routes.make(repos, testMode)

    EmberServerBuilder
      .default[IO]
      .withHost(host"0.0.0.0")
      .withPort(port)
      .withHttpApp(allRoutes.orNotFound)
      .build
  }
}
