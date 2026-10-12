package ssbudget.frontend.pages

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import ssbudget.frontend.services.{ApiClient, BankRenewal}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

/** Landing page for the Enable Banking redirect. Reads `code` + `state` from the URL and exchanges them via the backend. Then it goes on with a
  * [[BankRenewal]] run (the next bank's login), or returns to where the run started — the banking page for a plain connect.
  */
object BankingCallbackPage {

  private enum Step {
    case Finalizing
    case Continuing(bank: BankRenewal.Bank)
    case Failed(message: String)
  }

  def apply(apiClient: ApiClient): HtmlElement = {
    val step = Var(Step.Finalizing)

    def fail(message: String): Unit = {
      BankRenewal.clear() // never send the user on to another bank past an error they have not seen
      step.set(Step.Failed(message))
    }

    div(
      cls := "container py-5 text-center",
      onMountCallback { _ =>
        val params = new dom.URLSearchParams(dom.window.location.search)
        val code   = Option(params.get("code")).filter(_.nonEmpty)
        val state  = Option(params.get("state")).filter(_.nonEmpty)
        (code, state) match {
          case (Some(c), Some(s)) =>
            apiClient.banking.callback(c, s).onComplete {
              case Success(_)  =>
                BankRenewal
                  .continue(apiClient)(ex => fail(s"Could not start the next bank login: ${ex.getMessage}"))
                  .foreach(next => step.set(Step.Continuing(next)))
              case Failure(ex) => fail(ex.getMessage)
            }
          case _                  =>
            fail("Missing authorization code or state in the callback URL.")
        }
      },
      child <-- step.signal.map {
        case Step.Failed(err)      =>
          div(
            div(cls := "text-danger fs-5 mb-3", "Could not complete the bank connection"),
            div(cls := "text-muted mb-3", err),
            a(cls   := "btn btn-primary", href := "/banking", "Back to Bank Connections"),
          )
        case Step.Continuing(next) => spinner(s"Connected. Continuing to ${next.name}...")
        case Step.Finalizing       => spinner("Finalizing your bank connection...")
      },
    )
  }

  private def spinner(text: String): HtmlElement =
    div(div(cls := "spinner-border text-primary mb-3", role := "status"), div(cls := "text-muted", text))
}
