package ssbudget.frontend.pages

import com.raquo.laminar.api.L.*
import ssbudget.frontend.auth.AuthState
import ssbudget.frontend.services.ApiClient
import ssbudget.frontend.util.WebAuthnFacade

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

object LoginPage {

  def apply(apiClient: ApiClient, hasPasskeys: Boolean): HtmlElement = {
    val passwordVar       = Var("")
    val errorVar          = Var(Option.empty[String])
    val isSubmittingVar   = Var(false)
    val passkeyLoadingVar = Var(false)

    def loginWithPasskey(): Unit = {
      passkeyLoadingVar.set(true)
      errorVar.set(None)

      apiClient.auth.loginPasskeyStart().onComplete {
        case Success(options) =>
          WebAuthnFacade.getCredential(options).onComplete {
            case Success(response) =>
              apiClient.auth.loginPasskeyFinish(response).onComplete {
                case Success(_)  =>
                  passkeyLoadingVar.set(false)
                  AuthState.setLoggedIn()
                case Failure(ex) =>
                  passkeyLoadingVar.set(false)
                  errorVar.set(Some(s"Passkey authentication failed: ${ex.getMessage}"))
              }
            case Failure(ex)       =>
              passkeyLoadingVar.set(false)
              errorVar.set(Some(s"WebAuthn failed: ${ex.getMessage}"))
          }
        case Failure(ex)      =>
          passkeyLoadingVar.set(false)
          errorVar.set(Some(s"Failed to start authentication: ${ex.getMessage}"))
      }
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
              h3(cls := "card-title text-center mb-4", "SSBudget"),
              p(cls  := "text-muted text-center mb-4", "Sign in to continue"),
              child.maybe <-- errorVar.signal.map(_.map { error =>
                div(cls := "alert alert-danger", error)
              }),
              form(
                onSubmit.preventDefault --> { _ =>
                  val password = passwordVar.now()

                  if password.isEmpty then {
                    errorVar.set(Some("Password is required"))
                  } else {
                    isSubmittingVar.set(true)
                    errorVar.set(None)

                    apiClient.auth.login(password).onComplete {
                      case Success(_)  =>
                        AuthState.setLoggedIn()
                      case Failure(ex) =>
                        isSubmittingVar.set(false)
                        errorVar.set(Some("Invalid password"))
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
                    autoFocus   := true,
                  ),
                ),
                button(
                  cls := "btn btn-primary w-100",
                  tpe := "submit",
                  disabled <-- isSubmittingVar.signal.combineWith(passwordVar.signal).map { case (submitting, pass) =>
                    submitting || pass.isEmpty
                  },
                  child.text <-- isSubmittingVar.signal.map {
                    case true  => "Signing in..."
                    case false => "Sign In"
                  },
                ),
              ),
              if hasPasskeys && WebAuthnFacade.isSupported then {
                div(
                  cls := "mt-3",
                  hr(),
                  button(
                    cls := "btn btn-outline-secondary w-100",
                    disabled <-- passkeyLoadingVar.signal,
                    onClick --> { _ => loginWithPasskey() },
                    child.text <-- passkeyLoadingVar.signal.map {
                      case true  => "Authenticating..."
                      case false => "Sign in with Passkey"
                    },
                  ),
                )
              } else {
                emptyNode
              },
            ),
          ),
        ),
      ),
    )
  }
}
