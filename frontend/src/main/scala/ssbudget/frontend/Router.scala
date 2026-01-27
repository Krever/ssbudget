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
      ),
      getPageTitle = {
        case Page.Dashboard => "SSBudget - Dashboard"
        case Page.Budget    => "SSBudget - Budget"
        case Page.Accounts  => "SSBudget - Accounts"
        case Page.Periods   => "SSBudget - Periods"
        case Page.NotFound  => "SSBudget - Not Found"
      },
      serializePage = {
        case Page.Dashboard => "/"
        case Page.Budget    => "/budget"
        case Page.Accounts  => "/accounts"
        case Page.Periods   => "/periods"
        case Page.NotFound  => "/404"
      },
      deserializePage = {
        case "/"         => Page.Dashboard
        case "/budget"   => Page.Budget
        case "/accounts" => Page.Accounts
        case "/periods"  => Page.Periods
        case _           => Page.NotFound
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
