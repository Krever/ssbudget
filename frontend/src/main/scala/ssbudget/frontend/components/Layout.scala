package ssbudget.frontend.components

import com.raquo.laminar.api.L.*
import ssbudget.frontend.{Page, Router}
import ssbudget.frontend.pages.*

object Layout {

  def apply(): HtmlElement = {
    div(
      NavBar(),
      div(
        cls       := "main-content mx-auto",
        styleAttr := "max-width: 1600px",
        child <-- Router.currentPageSignal.map(renderPage),
      ),
    )
  }

  private def renderPage(page: Page): HtmlElement = {
    page match {
      case Page.Dashboard => DashboardPage()
      case Page.Budget    => BudgetPage()
      case Page.Accounts  => AccountsPage()
      case Page.Periods   => PeriodsPage()
      case Page.NotFound  => NotFoundPage()
    }
  }
}
