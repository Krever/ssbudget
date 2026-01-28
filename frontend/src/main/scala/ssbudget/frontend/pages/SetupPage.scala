package ssbudget.frontend.pages

import com.raquo.laminar.api.L.*
import ssbudget.frontend.auth.AuthState
import ssbudget.frontend.services.ApiClient

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

object SetupPage {

  def apply(apiClient: ApiClient): HtmlElement = {
    val passwordVar     = Var("")
    val confirmVar      = Var("")
    val errorVar        = Var(Option.empty[String])
    val isSubmittingVar = Var(false)

    val isValidSignal = passwordVar.signal
      .combineWith(confirmVar.signal)
      .combineWith(isSubmittingVar.signal)
      .map { case (password, confirm, submitting) =>
        password.nonEmpty && password == confirm && !submitting
      }

    div(
      cls := "container",
      div(
        cls := "row justify-content-center align-items-center min-vh-100",
        div(
          cls := "col-md-6 col-lg-4",
          div(
            cls := "card shadow",
            div(
              cls := "card-body p-4",
              h3(cls := "card-title text-center mb-4", "SSBudget Setup"),
              p(
                cls  := "text-muted text-center mb-4",
                "Create a password to secure your budget app.",
              ),
              child.maybe <-- errorVar.signal.map(_.map { error =>
                div(cls := "alert alert-danger", error)
              }),
              form(
                onSubmit.preventDefault --> { _ =>
                  val password = passwordVar.now()
                  val confirm  = confirmVar.now()

                  if password.isEmpty then {
                    errorVar.set(Some("Password is required"))
                  } else if password != confirm then {
                    errorVar.set(Some("Passwords do not match"))
                  } else {
                    isSubmittingVar.set(true)
                    errorVar.set(None)

                    apiClient.auth.setup(password).onComplete {
                      case Success(_)  =>
                        // Setup now returns a session cookie, so we're automatically logged in
                        AuthState.setLoggedIn()
                      case Failure(ex) =>
                        isSubmittingVar.set(false)
                        errorVar.set(Some(ex.getMessage))
                    }
                  }
                },
                div(
                  cls := "mb-3",
                  label(cls     := "form-label", forId := "password", "Password"),
                  input(
                    cls         := "form-control",
                    idAttr      := "password",
                    tpe         := "password",
                    placeholder := "Enter password",
                    controlled(
                      value <-- passwordVar.signal,
                      onInput.mapToValue --> passwordVar.writer,
                    ),
                  ),
                ),
                div(
                  cls := "mb-3",
                  label(cls     := "form-label", forId := "confirm", "Confirm Password"),
                  input(
                    cls         := "form-control",
                    idAttr      := "confirm",
                    tpe         := "password",
                    placeholder := "Confirm password",
                    controlled(
                      value <-- confirmVar.signal,
                      onInput.mapToValue --> confirmVar.writer,
                    ),
                  ),
                ),
                button(
                  cls := "btn btn-primary w-100",
                  tpe := "submit",
                  disabled <-- isValidSignal.map(!_),
                  child.text <-- isSubmittingVar.signal.map {
                    case true  => "Setting up..."
                    case false => "Create Password"
                  },
                ),
              ),
            ),
          ),
        ),
      ),
    )
  }
}
