package ssbudget.frontend.components

import com.raquo.laminar.api.L.*
import ssbudget.frontend.{Page, Router}
import ssbudget.frontend.services.{ApiClient, Notices}

import scala.concurrent.ExecutionContext.Implicits.global

/** The strip above every page for what must not stay hidden: a write that failed (until dismissed) and warnings about the data (while they are true).
  * It takes no room when there is nothing to say.
  */
object NoticeStrip {

  def apply(apiClient: ApiClient): HtmlElement =
    div(
      // Read on mount and on each page change: a rate refresh or a sync elsewhere can make a warning true or false. With no answer the strip
      // stays as it was.
      Router.currentPageSignal.distinctBy(Page.kindOf) --> { _ => apiClient.warnings.list().foreach(Notices.warnings.set) },
      cls       := "notice-strip mx-auto",
      styleAttr := "max-width: 1600px",
      children <-- Notices.failures.signal.map(_.map { failure =>
        div(
          cls                := "alert alert-danger d-flex align-items-center py-1 px-2 mb-1 small",
          role               := "alert",
          dataAttr("notice") := "failure",
          span(cls   := "me-auto", strong("Not done. "), failure.text),
          button(tpe := "button", cls := "btn-close btn-sm", aria.label := "Dismiss", onClick --> { _ => Notices.dismiss(failure.id) }),
        )
      }),
      children <-- Notices.warnings.signal.map(_.map { warning =>
        div(cls := "alert alert-warning py-1 px-2 mb-1 small", role := "status", dataAttr("notice") := warning.code, warning.message)
      }),
    )
}
