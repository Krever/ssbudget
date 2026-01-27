package ssbudget.frontend.pages

import com.raquo.laminar.api.L.*
import ssbudget.frontend.{Page, Router}

object NotFoundPage {

  def apply(): HtmlElement = {
    div(
      cls := "container mt-5",
      div(
        cls := "text-center",
        h1(cls := "display-1", "404"),
        p(cls  := "lead", "Page not found"),
        a(
          cls  := "btn btn-primary",
          href := "/",
          Router.linkTo(Page.Dashboard),
          "Go to Dashboard",
        ),
      ),
    )
  }
}
