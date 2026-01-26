package ssbudget.frontend

import com.raquo.laminar.api.L.{*, given}
import org.scalajs.dom
import sttp.client3.*
import sttp.tapir.DecodeResult
import sttp.tapir.client.sttp.SttpClientInterpreter

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

import ssbudget.shared.api.HealthEndpoint

object Main {

  private val backend = FetchBackend()

  def main(args: Array[String]): Unit = {
    val container = dom.document.getElementById("app")
    render(container, App.view)
  }

  object App {
    private val healthStatus = Var("Loading...")

    def view: HtmlElement = {
      div(
        cls := "container mt-5",
        div(
          cls := "box",
          h1(cls := "title", "SSBudget"),
          p(
            cls  := "subtitle",
            "Health Status: ",
            span(
              cls := "tag is-info",
              child.text <-- healthStatus.signal,
            ),
          ),
        ),
        onMountCallback { _ =>
          fetchHealth()
        },
      )
    }

    private def fetchHealth(): Unit = {
      val request = SttpClientInterpreter()
        .toRequest(HealthEndpoint.health, Some(uri"${dom.window.location.origin}"))
        .apply(())

      request.send(backend).map(_.body).foreach {
        case DecodeResult.Value(Right(response)) => healthStatus.set(response)
        case _                                   => healthStatus.set("Error")
      }
    }
  }
}
