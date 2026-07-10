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
        child <-- Router.currentPageSignal.map(page => renderPage(page, apiClient)),
      ),
    )
  }

  private def renderPage(page: Page, apiClient: ApiClient): HtmlElement = {
    page match {
      case Page.Dashboard       => DashboardPage()
      case Page.Budget          => BudgetPage()
      case Page.Accounts        => AccountsPage()
      case Page.Periods         => PeriodsPage()
      case Page.OneTimeExpenses => OneTimeExpensesPage()
      case Page.Banking         => BankingPage(apiClient)
      case Page.BankingCallback => BankingCallbackPage(apiClient)
      case Page.Transactions    => TransactionsPage(apiClient)
      case Page.Settings        => SettingsPage(apiClient)
      case Page.NotFound        => NotFoundPage()
    }
  }
}
