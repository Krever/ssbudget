package ssbudget.frontend.pages

import com.raquo.laminar.api.L.*
import ssbudget.frontend.auth.AuthState
import ssbudget.frontend.services.ApiClient
import ssbudget.frontend.util.WebAuthnFacade
import ssbudget.shared.api.PasskeyInfo

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

object SettingsPage {

  def apply(apiClient: ApiClient): HtmlElement = {
    val passkeysVar      = Var(List.empty[PasskeyInfo])
    val loadingVar       = Var(true)
    val errorVar         = Var(Option.empty[String])
    val successVar       = Var(Option.empty[String])
    val addingPasskeyVar = Var(false)
    val passkeyNameVar   = Var("")

    def loadPasskeys(): Unit = {
      loadingVar.set(true)
      apiClient.auth.listPasskeys().onComplete {
        case Success(keys) =>
          passkeysVar.set(keys)
          loadingVar.set(false)
        case Failure(ex)   =>
          errorVar.set(Some(s"Failed to load passkeys: ${ex.getMessage}"))
          loadingVar.set(false)
      }
    }

    def addPasskey(): Unit = {
      val name = passkeyNameVar.now()
      addingPasskeyVar.set(true)
      errorVar.set(None)
      successVar.set(None)

      apiClient.auth.registerPasskeyStart(if name.nonEmpty then Some(name) else None).onComplete {
        case Success(options) =>
          WebAuthnFacade.createCredential(options).onComplete {
            case Success(response) =>
              apiClient.auth.registerPasskeyFinish(response).onComplete {
                case Success(_)  =>
                  addingPasskeyVar.set(false)
                  passkeyNameVar.set("")
                  successVar.set(Some("Passkey added successfully"))
                  loadPasskeys()
                case Failure(ex) =>
                  addingPasskeyVar.set(false)
                  errorVar.set(Some(s"Failed to register passkey: ${ex.getMessage}"))
              }
            case Failure(ex)       =>
              addingPasskeyVar.set(false)
              errorVar.set(Some(s"WebAuthn failed: ${ex.getMessage}"))
          }
        case Failure(ex)      =>
          addingPasskeyVar.set(false)
          errorVar.set(Some(s"Failed to start registration: ${ex.getMessage}"))
      }
    }

    def deletePasskey(credentialId: String): Unit = {
      errorVar.set(None)
      successVar.set(None)
      apiClient.auth.deletePasskey(credentialId).onComplete {
        case Success(_)  =>
          successVar.set(Some("Passkey deleted"))
          loadPasskeys()
        case Failure(ex) =>
          errorVar.set(Some(s"Failed to delete passkey: ${ex.getMessage}"))
      }
    }

    div(
      cls := "container py-4",
      onMountCallback { _ => loadPasskeys() },
      h2(cls := "mb-4", "Settings"),

      // Messages
      child.maybe <-- errorVar.signal.map(_.map { error =>
        div(
          cls := "alert alert-danger alert-dismissible",
          error,
          button(
            tpe := "button",
            cls := "btn-close",
            onClick --> { _ =>
              errorVar.set(None)
            },
          ),
        )
      }),
      child.maybe <-- successVar.signal.map(_.map { msg =>
        div(
          cls := "alert alert-success alert-dismissible",
          msg,
          button(
            tpe := "button",
            cls := "btn-close",
            onClick --> { _ =>
              successVar.set(None)
            },
          ),
        )
      }),

      // Passkeys section
      div(
        cls := "card mb-4",
        div(
          cls := "card-header d-flex justify-content-between align-items-center",
          h5(cls := "mb-0", "Passkeys"),
          child <-- addingPasskeyVar.signal.map { adding =>
            if adding then {
              span(cls := "spinner-border spinner-border-sm")
            } else {
              emptyNode
            }
          },
        ),
        div(
          cls := "card-body",
          child <-- loadingVar.signal.map { loading =>
            if loading then {
              div(cls := "text-center py-3", div(cls := "spinner-border text-primary"))
            } else {
              div(
                child <-- passkeysVar.signal.map { keys =>
                  if keys.isEmpty then {
                    p(cls := "text-muted", "No passkeys registered. Add one for passwordless login.")
                  } else {
                    ul(
                      cls := "list-group list-group-flush mb-3",
                      keys.map { key =>
                        li(
                          cls := "list-group-item d-flex justify-content-between align-items-center",
                          div(
                            strong(key.displayName.getOrElse("Unnamed passkey")),
                            br(),
                            small(cls := "text-muted", s"Added: ${key.createdAt.toString.take(10)}"),
                            key.lastUsedAt
                              .map { used =>
                                small(cls := "text-muted ms-2", s"Last used: ${used.toString.take(10)}")
                              }
                              .getOrElse(emptyNode),
                          ),
                          button(
                            cls := "btn btn-outline-danger btn-sm",
                            "Delete",
                            onClick --> { _ => deletePasskey(key.credentialId) },
                          ),
                        )
                      },
                    )
                  }
                },

                // Add passkey form
                if WebAuthnFacade.isSupported then {
                  div(
                    cls := "mt-3",
                    div(
                      cls := "input-group",
                      input(
                        cls         := "form-control",
                        tpe         := "text",
                        placeholder := "Passkey name (optional)",
                        controlled(
                          value <-- passkeyNameVar.signal,
                          onInput.mapToValue --> passkeyNameVar.writer,
                        ),
                      ),
                      button(
                        cls         := "btn btn-primary",
                        disabled <-- addingPasskeyVar.signal,
                        "Add Passkey",
                        onClick --> { _ => addPasskey() },
                      ),
                    ),
                  )
                } else {
                  div(
                    cls := "alert alert-warning mt-3",
                    "WebAuthn is not supported in this browser. Passkeys are not available.",
                  )
                },
              )
            }
          },
        ),
      ),

      // Account section
      div(
        cls := "card",
        div(cls := "card-header", h5(cls := "mb-0", "Account")),
        div(
          cls   := "card-body",
          button(
            cls := "btn btn-outline-danger",
            "Logout",
            onClick --> { _ =>
              AuthState.logout(apiClient)
            },
          ),
        ),
      ),
    )
  }
}
