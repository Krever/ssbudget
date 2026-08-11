package ssbudget.frontend

import com.raquo.laminar.api.L.*
import com.raquo.waypoint.*
import io.circe.syntax.*
import org.scalajs.dom

object Router
    extends com.raquo.waypoint.Router[Page](
      routes = List(
        Route.static(Page.Dashboard, root / endOfSegments),
        Route.static(Page.Budget, root / "budget" / endOfSegments),
        Route.static(Page.Accounts, root / "accounts" / endOfSegments),
        Route.static(Page.Periods, root / "periods" / endOfSegments),
        Route.static(Page.Banking, root / "banking" / endOfSegments),
        Route.static(Page.BankingCallback, root / "banking" / "callback" / endOfSegments),
        // Filters live in the query string, so a filtered list is linkable (category drill-down) and survives a reload.
        Route.onlyQuery[Page.Transactions, (Option[String], Option[String], Option[String], Option[Boolean])](
          encode = p => (p.category, p.month, p.account, p.hideInternal),
          decode = { case (category, month, account, hideInternal) => Page.Transactions(category, month, account, hideInternal) },
          pattern = (root / "transactions" / endOfSegments) ? (param[String]("category").?
            & param[String]("month").?
            & param[String]("account").?
            & param[Boolean]("hideInternal").?),
        ),
        Route.static(Page.Analytics, root / "analytics" / endOfSegments),
        Route.static(Page.Settings, root / "settings" / endOfSegments),
      ),
      getPageTitle = {
        case Page.Dashboard       => "SSBudget - Dashboard"
        case Page.Budget          => "SSBudget - Budget"
        case Page.Accounts        => "SSBudget - Accounts"
        case Page.Periods         => "SSBudget - Periods"
        case Page.Banking         => "SSBudget - Bank Connections"
        case Page.BankingCallback => "SSBudget - Connecting..."
        case _: Page.Transactions => "SSBudget - Transactions"
        case Page.Analytics       => "SSBudget - Analytics"
        case Page.Settings        => "SSBudget - Settings"
        case Page.NotFound        => "SSBudget - Not Found"
      },
      // History state round-trips as JSON: the Transactions page carries filter arguments, so a bare path string no longer describes a page.
      serializePage = _.asJson.noSpaces,
      deserializePage = str => io.circe.parser.decode[Page](str).getOrElse(Page.NotFound),
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
