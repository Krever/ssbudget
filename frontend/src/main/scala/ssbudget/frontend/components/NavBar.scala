package ssbudget.frontend.components

import com.raquo.laminar.api.L.*
import ssbudget.frontend.{Page, Router}

object NavBar {

  def apply(): HtmlElement = {
    navTag(
      cls := "navbar navbar-expand-lg navbar-dark bg-dark",
      div(
        cls := "container-fluid",
        a(
          cls                   := "navbar-brand",
          href                  := "/",
          Router.linkTo(Page.Dashboard),
          "SSBudget",
        ),
        button(
          cls                   := "navbar-toggler",
          tpe                   := "button",
          dataAttr("bs-toggle") := "collapse",
          dataAttr("bs-target") := "#navbarNav",
          span(cls := "navbar-toggler-icon"),
        ),
        div(
          cls                   := "collapse navbar-collapse",
          idAttr                := "navbarNav",
          ul(
            cls := "navbar-nav",
            navItem(Page.Dashboard, "Dashboard"),
            navItem(Page.Budget, "Budget"),
            navItem(Page.Accounts, "Accounts"),
            navItem(Page.Periods, "Periods"),
          ),
        ),
      ),
    )
  }

  private def navItem(page: Page, label: String): HtmlElement = {
    li(
      cls := "nav-item",
      a(
        cls <-- Router.currentPageSignal.map { currentPage =>
          if currentPage == page then "nav-link active"
          else "nav-link"
        },
        href := Router.absoluteUrlForPage(page),
        Router.linkTo(page),
        label,
      ),
    )
  }
}
