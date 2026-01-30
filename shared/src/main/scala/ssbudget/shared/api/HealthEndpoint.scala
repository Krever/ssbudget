package ssbudget.shared.api

import sttp.tapir.*

object HealthEndpoint {
  val health: Endpoint[Unit, Unit, Unit, String, Any] = endpoint.get
    .in("api" / "health")
    .out(stringBody)
}
