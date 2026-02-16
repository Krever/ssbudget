package ssbudget.frontend.components

import com.raquo.laminar.api.L.*
import ssbudget.frontend.{Page, Router}
import ssbudget.frontend.auth.AuthState
import ssbudget.frontend.services.ApiClient

import scala.concurrent.ExecutionContext.Implicits.global

object NavBar {

  def apply(apiClient: ApiClient): HtmlElement = {
    val isOpen = Var(false)

    navTag(
      cls := "navbar navbar-expand-lg navbar-dark bg-dark",
      div(
        cls := "container-fluid",
        a(
          cls    := "navbar-brand",
          href   := "/",
          Router.linkTo(Page.Dashboard),
          "SSBudget",
        ),
        button(
          cls    := "navbar-toggler",
          tpe    := "button",
          onClick --> { _ => isOpen.update(!_) },
          span(cls := "navbar-toggler-icon"),
        ),
        div(
          cls <-- isOpen.signal.map { open =>
            if open then "collapse navbar-collapse show"
            else "collapse navbar-collapse"
          },
          idAttr := "navbarNav",
          ul(
            cls := "navbar-nav me-auto",
            navItem(Page.Dashboard, "Dashboard", isOpen),
            navItem(Page.Budget, "Budget", isOpen),
            navItem(Page.Accounts, "Accounts", isOpen),
            navItem(Page.Periods, "Periods", isOpen),
            navItem(Page.OneTimeExpenses, "One-Time", isOpen),
          ),
          ul(
            cls := "navbar-nav",
            navItem(Page.Settings, "Settings", isOpen),
            li(
              cls := "nav-item",
              button(
                cls := "btn btn-outline-light btn-sm ms-2",
                "Logout",
                onClick --> { _ =>
                  AuthState.logout(apiClient)
                },
              ),
            ),
          ),
        ),
      ),
    )
  }

  private def navItem(page: Page, label: String, isOpen: Var[Boolean]): HtmlElement = {
    li(
      cls := "nav-item",
      a(
        cls <-- Router.currentPageSignal.map { currentPage =>
          if currentPage == page then "nav-link active"
          else "nav-link"
        },
        href := Router.absoluteUrlForPage(page),
        Router.linkTo(page),
        onClick --> { _ => isOpen.set(false) },
        label,
      ),
    )
  }
}
