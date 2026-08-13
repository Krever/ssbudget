package ssbudget.frontend.pages

import com.raquo.laminar.api.L.*
import ssbudget.frontend.components.{Loading, LoadingState}
import ssbudget.frontend.services.{ApiClient, DataService}
import ssbudget.frontend.util.{Formatting, MoneyFormatter}
import ssbudget.frontend.{Page, Router}
import ssbudget.shared.api.{CategoryFilter, MonthFilter, PeriodCategorySpend, PeriodSummary}
import ssbudget.shared.model.PeriodId

import java.time.{LocalDate, ZoneId}
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

/** Period bookkeeping and the retrospective that goes with it: the running period on top (start, progress, and the one button that closes it), then
  * every past period as a row of what actually happened — money in and out per the bank, how the plan was executed, what savings did, and the balance
  * it ended on. Expanding a row shows where its spend went.
  */
object PeriodsPage {

  private val dataService = DataService.instance

  def apply(apiClient: ApiClient): HtmlElement = {
    val summaries = Var[LoadingState[List[PeriodSummary]]](LoadingState.Loading)
    val expanded  = Var(Set.empty[PeriodId])

    def load(): Unit =
      apiClient.periods.summaries().onComplete {
        case Success(list) => summaries.set(LoadingState.Loaded(list))
        case Failure(ex)   => summaries.set(LoadingState.Error(ex.getMessage))
      }

    div(
      cls := "container-fluid mt-3",
      onMountCallback { _ =>
        dataService.initialize()
        load()
      },
      div(
        cls := "d-flex justify-content-between align-items-center mb-3",
        h4(cls := "mb-0", "Periods"),
        child.maybe <-- summaries.signal.map {
          case LoadingState.Loaded(s :: _) => Some(small(cls := "text-muted", s"Amounts in ${s.currency.code}, converted at latest rates"))
          case _                           => None
        },
      ),
      // Closing a period rewrites the history below it, so the same action reloads the retrospective.
      currentPeriodCard(() => dataService.startNewPeriod().map(_ => load())),
      periodHistoryCard(summaries.signal, expanded),
    )
  }

  private def currentPeriodCard(startNew: () => Future[Unit]): HtmlElement =
    div(
      cls := "card mb-3",
      div(cls := "card-header py-2", div("Current Period")),
      div(
        cls   := "card-body py-2",
        child <-- dataService.currentPeriod.map {
          case Some(period) =>
            div(
              div(
                cls       := "d-flex flex-wrap align-items-end justify-content-between gap-3 mb-2",
                div(
                  cls := "d-flex flex-wrap gap-4",
                  stat("Started", Formatting.formatDate(period.startDate)),
                  stat("Expected end", expectedEndDate()),
                  stat("Days left", child.text <-- dataService.daysRemainingInPeriod.map(_.toString)),
                  stat("Day", child.text <-- dataService.daysRemainingInPeriod.map(_ => (Formatting.daysElapsed(period.startDate) + 1).toString)),
                ),
                Loading.actionButton("End Period & Start New", startNew, "btn btn-warning btn-sm"),
              ),
              div(
                cls       := "progress",
                styleAttr := "height: 6px",
                div(
                  cls  := "progress-bar",
                  role := "progressbar",
                  styleAttr <-- dataService.daysRemainingInPeriod.map(_ => s"width: ${Formatting.periodProgress(period.startDate)}%"),
                ),
              ),
            )
          case None         =>
            div(
              cls := "d-flex justify-content-between align-items-center",
              span(cls := "text-muted", "No active period"),
              Loading.actionButton("Start New Period", startNew, "btn btn-primary btn-sm"),
            )
        },
      ),
    )

  private def stat(label: String, value: Modifier[HtmlElement]): HtmlElement =
    div(
      div(cls := "text-muted small lh-1", label),
      div(cls := "fw-semibold", value),
    )

  /** Columns: the disclosure caret + Start, Days, In, Out, Net, Planned, Savings, End balance. */
  private val historyColumns = 9

  private def periodHistoryCard(summaries: Signal[LoadingState[List[PeriodSummary]]], expanded: Var[Set[PeriodId]]): HtmlElement =
    div(
      cls := "card",
      div(cls := "card-header py-2", div("Period History")),
      div(
        cls   := "card-body p-0",
        table(
          cls := "table table-sm table-hover mb-0 align-middle",
          thead(
            tr(
              th(cls := "ps-3", "Start"),
              th(cls := "text-end", "Days"),
              th(cls := "text-end", "In"),
              th(cls := "text-end", "Out"),
              th(cls := "text-end", "Net"),
              th(cls := "text-end", "Planned paid"),
              th(cls := "text-end", "Savings"),
              th(cls := "text-end", "End balance"),
              th(cls := "pe-3", ""),
            ),
          ),
          children <-- summaries.combineWith(expanded.signal).map {
            case (LoadingState.Loading, _)         => List(messageBody("Loading period history…", "text-muted"))
            case (LoadingState.Error(msg), _)      => List(messageBody(s"Couldn't load period history: $msg", "text-danger"))
            case (LoadingState.Loaded(Nil), _)     => List(messageBody("No periods yet — start one above.", "text-muted"))
            case (LoadingState.Loaded(list), open) =>
              // The Transactions page can only reproduce two windows — the current period and the one before it — so only those rows get a link out.
              val previousId = list.find(_.period.endDate.isDefined).map(_.period.id)
              list.map { s =>
                val window =
                  if s.ongoing then Some(MonthFilter.CurrentPeriod)
                  else Option.when(previousId.contains(s.period.id))(MonthFilter.PreviousPeriod)
                periodBody(s, open.contains(s.period.id), window, expanded)
              }
          },
        ),
      ),
      div(
        cls   := "card-footer py-2 small text-muted",
        "In/Out come from bank transactions (internal transfers excluded), so cash and unconnected banks are invisible there. " +
          "“Planned paid” is what you recorded against planned items — it overlaps with Out rather than adding to it.",
      ),
    )

  private def messageBody(text: String, textCls: String): HtmlElement =
    tbody(tr(td(colSpan := historyColumns, cls := s"p-3 small $textCls", text)))

  /** One period as its own `<tbody>`: the summary row, plus a detail row while it's expanded. */
  private def periodBody(s: PeriodSummary, isExpanded: Boolean, linkWindow: Option[String], expanded: Var[Set[PeriodId]]): HtmlElement = {
    val plannedLabel =
      if s.plannedTotal == 0 then "—"
      else s"${money(s.plannedPaidCents)} / ${money(s.plannedEstimateCents)}"

    tbody(
      tr(
        cls       := (if s.ongoing then "table-active" else ""),
        styleAttr := "cursor: pointer",
        title     := "Show where this period's spend went",
        onClick --> { _ => expanded.update(set => if set.contains(s.period.id) then set - s.period.id else set + s.period.id) },
        td(
          cls  := "ps-3 text-nowrap",
          span(cls := "text-muted me-1", if isExpanded then "▾" else "▸"),
          Formatting.formatDate(s.period.startDate),
        ),
        td(cls := "text-end font-monospace", s.days.toString),
        td(cls := "text-end font-monospace text-success", signed(s.inflowCents)),
        td(cls := "text-end font-monospace text-danger", signed(-s.outflowCents)),
        td(cls := s"text-end font-monospace fw-bold ${amountCls(s.netCents)}", signed(s.netCents)),
        td(cls := "text-end font-monospace", plannedLabel),
        td(cls := s"text-end font-monospace ${amountCls(s.savingsChangeCents)}", signed(s.savingsChangeCents)),
        td(cls := "text-end font-monospace", s.endBalanceCents.fold("—")(money)),
        td(
          cls  := "pe-3 text-end",
          if s.ongoing then span(cls := "badge text-bg-success", "Active") else span(cls := "badge text-bg-secondary", "Closed"),
        ),
      ),
      if isExpanded then tr(td(colSpan := historyColumns, cls := "p-0", detailPanel(s, linkWindow))) else emptyNode,
    )
  }

  /** Expanded detail: how the plan was executed, and the categories the period's spend actually went to. `linkWindow` is the Transactions-page month
    * filter that reproduces this period's spend, when one exists.
    */
  private def detailPanel(s: PeriodSummary, linkWindow: Option[String]): HtmlElement =
    div(
      cls := "p-3 bg-light",
      div(
        cls := "row g-3",
        div(
          cls := "col-md-4",
          div(cls := "text-muted small text-uppercase mb-1", "Plan execution"),
          div(cls := "small", s"${s.plannedSettled} of ${s.plannedTotal} planned expenses settled"),
          div(cls := "small", s"Paid ${money(s.plannedPaidCents)} of ${money(s.plannedEstimateCents)} estimated"),
          div(cls := "small", s"Planned income received: ${money(s.incomeReceivedCents)}"),
          div(cls := "small text-muted mt-1", "Estimates shown are each item's current value — definitions aren't versioned."),
        ),
        div(
          cls := "col-md-8",
          div(
            cls := "d-flex justify-content-between align-items-baseline mb-1",
            span(cls := "text-muted small text-uppercase", "Where the money went"),
            linkWindow.fold[Node](emptyNode) { window =>
              a(
                cls := "small text-nowrap",
                Router.linkTo(Page.Transactions(category = Some(CategoryFilter.All), month = Some(window))),
                "open in Transactions →",
              )
            },
          ),
          if s.topCategories.isEmpty then div(cls := "small text-muted", "No categorized spend recorded in this period.")
          else {
            val max = s.topCategories.map(_.spentCents).max
            div(s.topCategories.map(c => categoryBar(c, max)))
          },
        ),
      ),
    )

  private def categoryBar(c: PeriodCategorySpend, maxCents: Long): HtmlElement = {
    val pct   = if maxCents <= 0 then 0.0 else c.spentCents.toDouble / maxCents * 100.0
    val color = c.category.color.filter(_.trim.nonEmpty).getOrElse("#6c757d")
    div(
      cls := "mb-1",
      div(
        cls       := "d-flex justify-content-between small",
        span(c.category.name),
        span(cls := "font-monospace", money(c.spentCents)),
      ),
      div(
        cls       := "progress",
        styleAttr := "height: 4px",
        div(cls := "progress-bar", role := "progressbar", styleAttr := s"width: $pct%; background-color: $color"),
      ),
    )
  }

  /** Major-unit amount without a currency code — the card header states the currency once, so repeating it in every cell just adds noise. */
  private def money(cents: Long): String = MoneyFormatter.formatBare(cents)

  private def signed(cents: Long): String = MoneyFormatter.formatSigned(cents)

  private def amountCls(cents: Long): String = MoneyFormatter.amountCls(cents)

  private def expectedEndDate(): String = {
    val today     = LocalDate.now(ZoneId.of("UTC"))
    val day25     = today.withDayOfMonth(25)
    val periodEnd = if today.getDayOfMonth < 25 then day25 else day25.plusMonths(1)
    Formatting.formatLocalDate(periodEnd)
  }
}
