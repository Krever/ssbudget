package ssbudget.frontend

import com.raquo.laminar.api.L.{*, given}
import org.scalajs.dom
import ssbudget.frontend.auth.AuthState
import ssbudget.frontend.components.{Layout, Loading, LoadingState}
import ssbudget.frontend.pages.{LoginPage, SetupPage}
import ssbudget.frontend.services.{ApiClient, DataService}
import ssbudget.frontend.util.MoneyFormatter

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

object Main {

  private val dataState: Var[LoadingState[Unit]] = Var(LoadingState.Loading)
  private val apiClient                          = new ApiClient()

  def main(args: Array[String]): Unit = {
    val container = dom.document.getElementById("app")

    // First check auth state
    AuthState.initialize(apiClient)

    render(container, appRoot())
  }

  private def appRoot(): HtmlElement = {
    div(
      child <-- AuthState.current.signal.map {
        case AuthState.Loading            => loadingView("Checking authentication...")
        case AuthState.NeedsSetup         => SetupPage(apiClient)
        case AuthState.NeedsLogin(hasKey) => LoginPage(apiClient, hasKey)
        case AuthState.LoggedIn           => mainAppView()
        case AuthState.Error(msg)         => authErrorView(msg)
      },
    )
  }

  private def mainAppView(): HtmlElement = {
    // When logged in, initialize data and show main app
    div(
      onMountCallback { _ =>
        if dataState.now() == LoadingState.Loading then {
          DataService.instance.initialize().onComplete {
            case Success(_)  =>
              // Initialize MoneyFormatter with currency data
              MoneyFormatter.init(DataService.instance.primaryCurrency, DataService.instance.exchangeRates)
              dataState.set(LoadingState.Loaded(()))
            case Failure(ex) =>
              dom.console.error(s"Failed to initialize: ${ex.getMessage}")
              dataState.set(LoadingState.Error(s"Failed to load data: ${ex.getMessage}"))
          }
        }
      },
      child <-- dataState.signal.map {
        case LoadingState.Loading    => loadingView("Loading data...")
        case LoadingState.Loaded(_)  => Layout(apiClient)
        case LoadingState.Error(msg) => errorView(msg)
      },
    )
  }

  private def loadingView(message: String): HtmlElement = {
    div(
      cls := "d-flex justify-content-center align-items-center vh-100",
      div(
        cls := "text-center",
        div(cls := "spinner-border text-primary mb-3", role := "status"),
        div(cls := "text-muted", message),
      ),
    )
  }

  private def authErrorView(message: String): HtmlElement = {
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
            AuthState.initialize(apiClient)
          },
        ),
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
            dataState.set(LoadingState.Loading)
            DataService.instance.initialize().onComplete {
              case Success(_)  =>
                MoneyFormatter.init(DataService.instance.primaryCurrency, DataService.instance.exchangeRates)
                dataState.set(LoadingState.Loaded(()))
              case Failure(ex) => dataState.set(LoadingState.Error(s"Failed to load data: ${ex.getMessage}"))
            }
          },
        ),
      ),
    )
  }
}
