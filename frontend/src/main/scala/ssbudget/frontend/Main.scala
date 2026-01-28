package ssbudget.frontend

import com.raquo.laminar.api.L.{*, given}
import org.scalajs.dom
import ssbudget.frontend.components.{Layout, Loading, LoadingState}
import ssbudget.frontend.services.DataService

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

object Main {

  private val appState: Var[LoadingState[Unit]] = Var(LoadingState.Loading)

  def main(args: Array[String]): Unit = {
    val container = dom.document.getElementById("app")

    // Initialize the data service
    DataService.instance.initialize().onComplete {
      case Success(_)  => appState.set(LoadingState.Loaded(()))
      case Failure(ex) =>
        dom.console.error(s"Failed to initialize: ${ex.getMessage}")
        appState.set(LoadingState.Error(s"Failed to load data: ${ex.getMessage}"))
    }

    render(container, appRoot())
  }

  private def appRoot(): HtmlElement = {
    div(
      child <-- appState.signal.map {
        case LoadingState.Loading    => loadingView()
        case LoadingState.Loaded(_)  => Layout()
        case LoadingState.Error(msg) => errorView(msg)
      },
    )
  }

  private def loadingView(): HtmlElement = {
    div(
      cls := "d-flex justify-content-center align-items-center vh-100",
      div(
        cls := "text-center",
        div(cls := "spinner-border text-primary mb-3", role := "status"),
        div(cls := "text-muted", "Loading SSBudget..."),
      ),
    )
  }

  private def errorView(message: String): HtmlElement = {
    div(
      cls := "d-flex justify-content-center align-items-center vh-100",
      div(
        cls := "text-center",
        div(cls := "text-danger fs-1 mb-3", "!"),
        div(cls := "text-danger", message),
        button(
          cls   := "btn btn-primary mt-3",
          "Retry",
          onClick --> { _ =>
            appState.set(LoadingState.Loading)
            DataService.instance.initialize().onComplete {
              case Success(_)  => appState.set(LoadingState.Loaded(()))
              case Failure(ex) => appState.set(LoadingState.Error(s"Failed to load data: ${ex.getMessage}"))
            }
          },
        ),
      ),
    )
  }
}
