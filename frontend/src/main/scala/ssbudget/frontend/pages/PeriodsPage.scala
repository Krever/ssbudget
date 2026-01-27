package ssbudget.frontend.pages

import com.raquo.laminar.api.L.*
import ssbudget.frontend.services.DataService
import ssbudget.frontend.util.Formatting
import ssbudget.shared.model.Period

object PeriodsPage {

  private val dataService = DataService.instance

  def apply(): HtmlElement = {
    div(
      cls := "container-fluid mt-3",
      h4("Periods"),
      div(
        cls := "row g-3",
        div(
          cls := "col-lg-6",
          currentPeriodCard(),
        ),
        div(
          cls := "col-lg-6",
          periodHistoryCard(),
        ),
      ),
    )
  }

  private def currentPeriodCard(): HtmlElement = {
    div(
      cls := "card",
      div(
        cls := "card-header py-2",
        "Current Period",
      ),
      div(
        cls := "card-body",
        child <-- dataService.currentPeriod.map {
          case Some(period) =>
            div(
              div(
                cls := "row mb-3",
                div(
                  cls := "col-6",
                  div(cls := "text-muted small", "Started"),
                  div(cls := "fw-bold", Formatting.formatDate(period.startDate)),
                ),
                div(
                  cls := "col-6",
                  div(cls := "text-muted small", "Days Remaining"),
                  div(
                    cls   := "fw-bold",
                    child.text <-- dataService.daysRemainingInPeriod.map(_.toString),
                  ),
                ),
              ),
              div(
                cls := "mb-3",
                div(cls     := "text-muted small mb-1", "Progress"),
                div(
                  cls       := "progress",
                  styleAttr := "height: 20px",
                  div(
                    cls  := "progress-bar",
                    role := "progressbar",
                    styleAttr <-- dataService.daysRemainingInPeriod.map { _ =>
                      val progress = Formatting.periodProgress(period.startDate)
                      s"width: $progress%"
                    },
                    child.text <-- dataService.daysRemainingInPeriod.map { _ =>
                      val progress = Formatting.periodProgress(period.startDate)
                      s"$progress%"
                    },
                  ),
                ),
              ),
              div(
                cls := "d-grid",
                button(
                  tpe := "button",
                  cls := "btn btn-warning",
                  "End Period & Start New",
                  onClick --> { _ => dataService.startNewPeriod() },
                ),
              ),
            )
          case None         =>
            div(
              cls := "text-center py-4",
              p(cls := "text-muted", "No active period"),
              button(
                tpe := "button",
                cls := "btn btn-primary",
                "Start New Period",
                onClick --> { _ => dataService.startNewPeriod() },
              ),
            )
        },
      ),
    )
  }

  private def periodHistoryCard(): HtmlElement = {
    div(
      cls := "card",
      div(
        cls := "card-header py-2",
        "Period History",
      ),
      div(
        cls := "card-body p-0",
        table(
          cls := "table table-sm table-hover mb-0",
          thead(
            tr(
              th("Start"),
              th("End"),
              th("Duration"),
              th("Status"),
            ),
          ),
          tbody(
            children <-- dataService.periods.map { periods =>
              periods.sortBy(_.startDate).reverse.map(periodRow)
            },
          ),
        ),
      ),
    )
  }

  private def periodRow(period: Period): HtmlElement = {
    val isActive = period.endDate.isEmpty
    val duration = period.endDate match {
      case Some(end) =>
        val days = java.time.temporal.ChronoUnit.DAYS.between(period.startDate, end).toInt
        s"$days days"
      case None      =>
        val days = Formatting.daysElapsed(period.startDate)
        s"$days days (ongoing)"
    }

    tr(
      cls := (if isActive then "table-active" else ""),
      td(Formatting.formatDate(period.startDate)),
      td(period.endDate.fold("-")(Formatting.formatDate)),
      td(duration),
      td(
        if isActive then span(cls := "badge text-bg-success", "Active")
        else span(cls             := "badge text-bg-secondary", "Closed"),
      ),
    )
  }
}
