package ssbudget.frontend.components

import com.raquo.laminar.api.L.*
import ssbudget.frontend.{Page, Router}
import ssbudget.frontend.pages.*
import ssbudget.frontend.services.ApiClient

object Layout {

  def apply(apiClient: ApiClient): HtmlElement = {
    div(
      NavBar(apiClient),
      div(
        cls       := "main-content mx-auto pb-4",
        styleAttr := "max-width: 1600px",
        // Keyed on the page KIND, not the page: the Transactions page carries its filters in the URL, and a filter change must not remount it.
        child <-- Router.currentPageSignal.distinctBy(Page.kindOf).map(page => renderPage(page, apiClient)),
      ),
    )
  }

  private def renderPage(page: Page, apiClient: ApiClient): HtmlElement = {
    page match {
      case Page.Dashboard       => DashboardPage()
      case Page.Accounts        => AccountsPage()
      case Page.Periods         => PeriodsPage(apiClient)
      case Page.Banking         => BankingPage(apiClient)
      case Page.BankingCallback => BankingCallbackPage(apiClient)
      case t: Page.Transactions => TransactionsPage(apiClient, t)
      case Page.Analytics       => AnalyticsPage(apiClient)
      case Page.Settings        => SettingsPage(apiClient)
      case Page.NotFound        => NotFoundPage()
    }
  }
}
