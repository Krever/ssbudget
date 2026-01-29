package ssbudget.frontend.pages

import com.raquo.laminar.api.L.*
import ssbudget.frontend.auth.AuthState
import ssbudget.frontend.components.Loading
import ssbudget.frontend.services.{ApiClient, DataService}
import ssbudget.frontend.util.WebAuthnFacade
import ssbudget.shared.api.PasskeyInfo
import ssbudget.shared.model.{Currency, CurrencySetting}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

object SettingsPage {

  // Custom attribute for datalist linking
  private val listAttr: HtmlAttr[String] = htmlAttr("list", com.raquo.laminar.codecs.StringAsIsCodec)

  private val dataService = DataService.instance

  def apply(apiClient: ApiClient): HtmlElement = {
    val passkeysVar        = Var(List.empty[PasskeyInfo])
    val loadingVar         = Var(true)
    val errorVar           = Var(Option.empty[String])
    val successVar         = Var(Option.empty[String])
    val addingPasskeyVar   = Var(false)
    val passkeyNameVar     = Var("")
    val addCurrencyCodeVar = Var("")
    val refreshingRatesVar = Var(false)

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

      // Currencies section
      currenciesCard(errorVar, successVar, addCurrencyCodeVar, refreshingRatesVar),

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

  private def currenciesCard(
      errorVar: Var[Option[String]],
      successVar: Var[Option[String]],
      addCurrencyCodeVar: Var[String],
      refreshingRatesVar: Var[Boolean],
  ): HtmlElement = {

    def refreshRates(): Unit = {
      refreshingRatesVar.set(true)
      errorVar.set(None)
      dataService.refreshExchangeRates().onComplete {
        case Success(_)  =>
          refreshingRatesVar.set(false)
          successVar.set(Some("Exchange rates refreshed"))
        case Failure(ex) =>
          refreshingRatesVar.set(false)
          errorVar.set(Some(s"Failed to refresh rates: ${ex.getMessage}"))
      }
    }

    def enableCurrency(): Unit = {
      val code = addCurrencyCodeVar.now().toUpperCase.trim
      if code.nonEmpty then {
        errorVar.set(None)
        dataService.enableCurrency(code).onComplete {
          case Success(_)  =>
            addCurrencyCodeVar.set("")
            successVar.set(Some(s"Currency $code enabled"))
          case Failure(ex) =>
            errorVar.set(Some(s"Failed to enable currency: ${ex.getMessage}"))
        }
      }
    }

    def disableCurrency(code: String): Unit = {
      errorVar.set(None)
      dataService.disableCurrency(code).onComplete {
        case Success(_)  =>
          successVar.set(Some(s"Currency $code disabled"))
        case Failure(ex) =>
          errorVar.set(Some(s"Failed to disable currency: ${ex.getMessage}"))
      }
    }

    def setPrimary(code: String): Unit = {
      errorVar.set(None)
      dataService.setPrimaryCurrency(code).onComplete {
        case Success(_)  =>
          successVar.set(Some(s"$code set as primary currency"))
        case Failure(ex) =>
          errorVar.set(Some(s"Failed to set primary currency: ${ex.getMessage}"))
      }
    }

    div(
      cls := "card mb-4",
      div(
        cls := "card-header d-flex justify-content-between align-items-center",
        h5(cls := "mb-0", "Currencies"),
        div(
          child <-- refreshingRatesVar.signal.map { refreshing =>
            if refreshing then {
              span(cls := "spinner-border spinner-border-sm me-2")
            } else {
              emptyNode
            }
          },
          button(
            cls := "btn btn-outline-primary btn-sm",
            disabled <-- refreshingRatesVar.signal,
            "Refresh Rates",
            onClick --> { _ => refreshRates() },
          ),
        ),
      ),
      div(
        cls := "card-body p-0",
        table(
          cls := "table table-sm table-hover mb-0",
          thead(
            tr(
              th("Code"),
              th("Name"),
              th(cls := "text-end", "Rate"),
              th("Actions"),
            ),
          ),
          tbody(
            children <-- dataService.currencySettings
              .combineWith(dataService.exchangeRates)
              .map { case (settings, rates) =>
                settings.map { setting =>
                  val rateStr =
                    if setting.isPrimary then "-"
                    else rates.get(setting.code).map(r => f"$r%.4f").getOrElse("N/A")
                  currencyRow(setting, rateStr, setPrimary, disableCurrency)
                }
              },
          ),
        ),
        // Add currency form with searchable dropdown
        div(
          cls := "p-3 border-top",
          child <-- dataService.availableCurrencies
            .combineWith(dataService.currencySettings)
            .map { case (available, enabled) =>
              val enabledCodes  = enabled.map(_.code.code).toSet
              val notYetEnabled = available.filterNot { case (code, _) => enabledCodes.contains(code) }
              div(
                div(
                  cls    := "input-group",
                  input(
                    cls         := "form-control",
                    tpe         := "text",
                    listAttr    := "available-currencies",
                    placeholder := "Search currency (e.g., USD, GBP)",
                    controlled(
                      value <-- addCurrencyCodeVar.signal,
                      onInput.mapToValue --> addCurrencyCodeVar.writer,
                    ),
                    onKeyPress --> { e =>
                      if e.key == "Enter" then enableCurrency()
                    },
                  ),
                  button(
                    cls         := "btn btn-outline-success",
                    "Add Currency",
                    onClick --> { _ => enableCurrency() },
                  ),
                ),
                dataList(
                  idAttr := "available-currencies",
                  notYetEnabled.map { case (code, name) =>
                    option(value := code, s"$code - $name")
                  },
                ),
                small(
                  cls    := "text-muted mt-1 d-block",
                  s"${notYetEnabled.size} currencies available",
                ),
              )
            },
        ),
      ),
    )
  }

  private def currencyRow(
      setting: CurrencySetting,
      rateStr: String,
      setPrimary: String => Unit,
      disableCurrency: String => Unit,
  ): HtmlElement = {
    val code = setting.code.code
    tr(
      td(
        span(cls := "badge text-bg-secondary me-1", code),
        if setting.isPrimary then span(cls := "badge text-bg-primary", "Primary") else emptyNode,
      ),
      td(setting.name),
      td(cls := "text-end font-monospace", rateStr),
      td(
        if setting.isPrimary then {
          emptyNode
        } else {
          div(
            cls := "btn-group btn-group-sm",
            button(
              cls := "btn btn-outline-primary btn-sm",
              "Set Primary",
              onClick --> { _ => setPrimary(code) },
            ),
            button(
              cls := "btn btn-outline-danger btn-sm",
              "Remove",
              onClick --> { _ => disableCurrency(code) },
            ),
          )
        },
      ),
    )
  }
}
