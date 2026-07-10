package ssbudget.frontend

import com.raquo.laminar.api.L.*
import com.raquo.waypoint.*
import org.scalajs.dom

object Router
    extends com.raquo.waypoint.Router[Page](
      routes = List(
        Route.static(Page.Dashboard, root / endOfSegments),
        Route.static(Page.Budget, root / "budget" / endOfSegments),
        Route.static(Page.Accounts, root / "accounts" / endOfSegments),
        Route.static(Page.Periods, root / "periods" / endOfSegments),
        Route.static(Page.OneTimeExpenses, root / "one-time-expenses" / endOfSegments),
        Route.static(Page.Banking, root / "banking" / endOfSegments),
        Route.static(Page.BankingCallback, root / "banking" / "callback" / endOfSegments),
        Route.static(Page.Transactions, root / "transactions" / endOfSegments),
        Route.static(Page.Analytics, root / "analytics" / endOfSegments),
        Route.static(Page.Settings, root / "settings" / endOfSegments),
      ),
      getPageTitle = {
        case Page.Dashboard       => "SSBudget - Dashboard"
        case Page.Budget          => "SSBudget - Budget"
        case Page.Accounts        => "SSBudget - Accounts"
        case Page.Periods         => "SSBudget - Periods"
        case Page.OneTimeExpenses => "SSBudget - One-Time Expenses"
        case Page.Banking         => "SSBudget - Bank Connections"
        case Page.BankingCallback => "SSBudget - Connecting..."
        case Page.Transactions    => "SSBudget - Transactions"
        case Page.Analytics       => "SSBudget - Analytics"
        case Page.Settings        => "SSBudget - Settings"
        case Page.NotFound        => "SSBudget - Not Found"
      },
      serializePage = {
        case Page.Dashboard       => "/"
        case Page.Budget          => "/budget"
        case Page.Accounts        => "/accounts"
        case Page.Periods         => "/periods"
        case Page.OneTimeExpenses => "/one-time-expenses"
        case Page.Banking         => "/banking"
        case Page.BankingCallback => "/banking/callback"
        case Page.Transactions    => "/transactions"
        case Page.Analytics       => "/analytics"
        case Page.Settings        => "/settings"
        case Page.NotFound        => "/404"
      },
      deserializePage = {
        case "/"                  => Page.Dashboard
        case "/budget"            => Page.Budget
        case "/accounts"          => Page.Accounts
        case "/periods"           => Page.Periods
        case "/one-time-expenses" => Page.OneTimeExpenses
        case "/banking/callback"  => Page.BankingCallback
        case "/banking"           => Page.Banking
        case "/transactions"      => Page.Transactions
        case "/analytics"         => Page.Analytics
        case "/settings"          => Page.Settings
        case _                    => Page.NotFound
      },
    ) {

  def linkTo(page: Page): Binder[HtmlElement] = {
    Binder { el =>
      val isLinkElement = el.ref.isInstanceOf[dom.html.Anchor]
      if isLinkElement then {
        el.amend(href := absoluteUrlForPage(page))
      }
      (onClick
        .filter(ev => !(ev.ctrlKey || ev.metaKey || ev.shiftKey || ev.altKey))
        .preventDefault
        --> (_ => pushState(page))).bind(el)
    }
  }
}
