package ssbudget.frontend.pages

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import ssbudget.frontend.services.ApiClient

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

/** Landing page for the Enable Banking redirect. Reads `code` + `state` from the URL, exchanges them via the backend, then returns to the banking
  * page.
  */
object BankingCallbackPage {

  def apply(apiClient: ApiClient): HtmlElement = {
    val errorVar = Var(Option.empty[String])

    div(
      cls := "container py-5 text-center",
      onMountCallback { _ =>
        val params = new dom.URLSearchParams(dom.window.location.search)
        val code   = Option(params.get("code")).filter(_.nonEmpty)
        val state  = Option(params.get("state")).filter(_.nonEmpty)
        (code, state) match {
          case (Some(c), Some(s)) =>
            apiClient.banking.callback(c, s).onComplete {
              case Success(_)  => dom.window.location.href = "/banking"
              case Failure(ex) => errorVar.set(Some(ex.getMessage))
            }
          case _                  =>
            errorVar.set(Some("Missing authorization code or state in the callback URL."))
        }
      },
      child <-- errorVar.signal.map {
        case Some(err) =>
          div(
            div(cls := "text-danger fs-5 mb-3", "Could not complete the bank connection"),
            div(cls := "text-muted mb-3", err),
            a(cls   := "btn btn-primary", href := "/banking", "Back to Bank Connections"),
          )
        case None      =>
          div(
            div(cls := "spinner-border text-primary mb-3", role := "status"),
            div(cls := "text-muted", "Finalizing your bank connection..."),
          )
      },
    )
  }
}
