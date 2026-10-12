package ssbudget.frontend.components

import com.raquo.laminar.api.L.*
import ssbudget.frontend.services.{ApiClient, BankRenewal}
import ssbudget.frontend.{Page, Router}
import ssbudget.shared.model.{BankConnection, ConnectionHealth}

import java.time.Instant
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

/** A one-line strip above every page while a bank connection needs a new login, so a lapsed consent is seen where the user is, not only on the
  * banking page. Each bank is a link that renews that one bank; "Renew all" logs in to every listed bank in turn (see [[BankRenewal]]).
  */
object BankRenewalBanner {

  def apply(apiClient: ApiClient): HtmlElement = {
    val entries  = Var(List.empty[(BankConnection, ConnectionHealth)]) // only the connections that need attention
    val starting = Var(false)
    val errorVar = Var(Option.empty[String])

    def load(): Unit =
      apiClient.banking.connections().onComplete {
        case Success(views) =>
          val now = Instant.now()
          entries.set(views.map(v => (v.connection, v.connection.health(now))).filter(_._2 != ConnectionHealth.Ok))
        case Failure(_)     => () // no banking configured, or a passing error: the strip is a hint, not a page
      }

    def renew(banks: List[BankRenewal.Bank]): Unit = {
      starting.set(true)
      errorVar.set(None)
      BankRenewal.start(apiClient, banks) { ex =>
        starting.set(false)
        errorVar.set(Some(ex.getMessage))
      }
    }

    div(
      // Read on mount and on each page change: a sync elsewhere can mark a connection expired, and a finished renewal must drop off.
      Router.currentPageSignal.map(Page.kindOf) --> { _ => load() },
      // Hidden on the callback page, which is itself the renewal in progress.
      child <-- entries.signal.combineWith(Router.currentPageSignal.map(_ == Page.BankingCallback)).map { case (es, onCallback) =>
        if es.isEmpty || onCallback then emptyNode
        else {
          val urgent = es.exists(_._2 == ConnectionHealth.NeedsRenewal)
          div(
            cls := s"alert ${if urgent then "alert-warning" else "alert-light border"} rounded-0 py-2 px-3 mb-0 d-flex flex-wrap align-items-center gap-2 small",
            span(if urgent then "⚠ Bank access:" else "Bank access ends soon:"),
            es.map { case (conn, health) =>
              button(
                cls   := "btn btn-link btn-sm p-0 align-baseline",
                title := s"Log in to ${conn.aspspName} again",
                disabled <-- starting.signal,
                s"${conn.aspspName} ${describe(health)}",
                onClick --> { _ => renew(List(BankRenewal.Bank.of(conn))) },
              )
            },
            button(
              cls := "btn btn-warning btn-sm py-0 ms-auto",
              disabled <-- starting.signal,
              child.text <-- starting.signal.map(if _ then "Opening bank..." else if es.size > 1 then s"Renew all (${es.size})" else "Renew"),
              onClick --> { _ => renew(es.map { case (conn, _) => BankRenewal.Bank.of(conn) }) },
            ),
            child.maybe <-- errorVar.signal.map(_.map(err => span(cls := "text-danger w-100", s"Could not start the bank login: $err"))),
            if es.size > 1 then span(cls := "text-muted w-100", "You log in to each bank in turn; the app moves on to the next one by itself.")
            else emptyNode,
          )
        }
      },
    )
  }

  private def describe(health: ConnectionHealth): String = health match {
    case ConnectionHealth.NeedsRenewal   => "(expired)"
    case ConnectionHealth.ExpiresSoon(0) => "(ends today)"
    case ConnectionHealth.ExpiresSoon(1) => "(ends in 1 day)"
    case ConnectionHealth.ExpiresSoon(d) => s"(ends in $d days)"
    case ConnectionHealth.Ok             => ""
  }
}
