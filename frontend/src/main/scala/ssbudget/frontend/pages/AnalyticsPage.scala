package ssbudget.frontend.pages

import com.raquo.laminar.api.L.*
import ssbudget.frontend.services.ApiClient
import ssbudget.frontend.util.{Chart, MoneyFormatter}
import ssbudget.shared.api.AnalyticsResponse
import ssbudget.shared.model.Category

import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.util.{Failure, Success}

/** Spending analytics: a per-month, per-category stacked bar chart (Chart.js — click a legend entry to hide/show that category, hover for the exact
  * breakdown), plus import/categorization health and the top counterparties still awaiting a category. All amounts arrive already converted to the
  * primary currency.
  */
object AnalyticsPage {

  // Fallback swatch palette for categories without an explicit color.
  private val palette = List(
    "#4e79a7",
    "#f28e2b",
    "#e15759",
    "#76b7b2",
    "#59a14f",
    "#edc948",
    "#b07aa1",
    "#ff9da7",
    "#9c755f",
    "#bab0ac",
  )

  private def colorFor(idx: Int, cat: Category): String =
    cat.color.filter(_.trim.nonEmpty).getOrElse(palette(idx % palette.size))

  /** Compact major-unit amount for the y-axis: 1.2k, 3.4M, 850. */
  private def compactMajor(v: Double): String = {
    val a = math.abs(v)
    if a >= 1000000 then f"${v / 1000000}%.1fM"
    else if a >= 1000 then f"${v / 1000}%.1fk"
    else f"$v%.0f"
  }

  /** Compact cents amount for the stat cards. */
  private def compact(cents: Long): String = compactMajor(cents / 100.0)

  /** "2025-07" -> "07/25". */
  private def monthLabel(ym: String): String =
    ym.split("-") match {
      case Array(y, m) => s"$m/${y.takeRight(2)}"
      case _           => ym
    }

  def apply(apiClient: ApiClient): HtmlElement = {
    val dataVar    = Var(Option.empty[AnalyticsResponse])
    val loadingVar = Var(true)
    val errorVar   = Var(Option.empty[String])

    def load(): Unit = {
      loadingVar.set(true)
      apiClient.analytics.overview(None).onComplete {
        case Success(r)  => dataVar.set(Some(r)); errorVar.set(None); loadingVar.set(false)
        case Failure(ex) => errorVar.set(Some(s"Failed to load analytics: ${ex.getMessage}")); loadingVar.set(false)
      }
    }

    div(
      cls := "container-fluid mt-3",
      onMountCallback(_ => load()),
      div(
        cls := "d-flex justify-content-between align-items-center mb-3",
        h4(cls := "mb-0", "Analytics"),
        child.maybe <-- dataVar.signal.map(_.map { d =>
          small(cls := "text-muted", s"Amounts in ${d.currency.code}, converted at latest rates")
        }),
      ),
      child.maybe <-- errorVar.signal.map(_.map { e =>
        div(cls := "alert alert-danger alert-dismissible", e, button(tpe := "button", cls := "btn-close", onClick --> { _ => errorVar.set(None) }))
      }),
      child <-- loadingVar.signal.combineWith(dataVar.signal).map {
        case (true, _)           => div(cls := "text-center text-muted py-5", "Loading analytics…")
        case (false, None)       => div(cls := "text-muted py-5 text-center", "No data.")
        case (false, Some(data)) => content(data)
      },
    )
  }

  private def content(data: AnalyticsResponse): HtmlElement =
    div(
      statsRow(data),
      chartCard(data),
      uncategorizedCard(data),
    )

  // ---- Categorization health ----------------------------------------------------------------

  private def statsRow(data: AnalyticsResponse): HtmlElement = {
    val s        = data.categorization
    val denom    = s.categorized + s.uncategorized
    val pct      = if denom == 0 then 0 else math.round(s.categorized * 100.0 / denom).toInt
    val winSpend = data.monthlyTotals.sum

    div(
      cls := "row g-3 mb-3",
      statCard("Transactions", s.total.toString, s"${s.internal} internal (excluded)", "text-body"),
      statCard("Categorized", s"$pct%", s"${s.categorized} of $denom · ${s.manual} manual · ${s.rule} by rule", "text-success"),
      statCard(
        "Uncategorized",
        s.uncategorized.toString,
        MoneyFormatter.formatSimple(s.uncategorizedOutflowCents, data.currency) + " to triage",
        if s.uncategorized > 0 then "text-danger" else "text-success",
      ),
      statCard("Spend", compact(winSpend), s"over ${data.months.size} months", "text-body"),
    )
  }

  private def statCard(title: String, value: String, sub: String, valueCls: String): HtmlElement =
    div(
      cls := "col-sm-6 col-lg-3",
      div(
        cls := "card h-100",
        div(
          cls := "card-body py-2",
          div(cls := "text-muted small text-uppercase", title),
          div(cls := s"fs-4 fw-semibold $valueCls", value),
          div(cls := "text-muted small", sub),
        ),
      ),
    )

  // ---- Monthly spending chart (Chart.js) ----------------------------------------------------

  private def chartCard(data: AnalyticsResponse): HtmlElement =
    div(
      cls := "card mb-3",
      div(
        cls := "card-header d-flex justify-content-between align-items-center",
        span("Monthly spending by category"),
        small(cls := "text-muted", "Click a category in the legend to hide it"),
      ),
      div(
        cls := "card-body",
        if data.series.isEmpty then div(cls := "text-muted text-center py-5", s"No spending recorded in the last ${data.months.size} months.")
        else chartCanvas(data),
      ),
    )

  private def chartCanvas(data: AnalyticsResponse): HtmlElement = {
    // Chart.js owns the <canvas>; create it on mount, tear it down on unmount. Data is fixed per page load, so no reactive update is needed.
    var chart: js.UndefOr[Chart] = js.undefined
    div(
      // A relatively-positioned, fixed-height box is what `maintainAspectRatio: false` needs to size the canvas.
      styleAttr := "position:relative;height:400px",
      canvasTag(
        onMountCallback { ctx => chart = new Chart(ctx.thisNode.ref, chartConfig(data)) },
        onUnmountCallback { _ => chart.foreach(_.destroy()); chart = js.undefined },
      ),
    )
  }

  private def chartConfig(data: AnalyticsResponse): js.Any = {
    val cur    = data.currency.code
    val labels = data.months.map(monthLabel).toJSArray

    val datasets = data.series.zipWithIndex.map { case (s, idx) =>
      js.Dynamic.literal(
        label = s.category.name,
        data = s.monthly.map(_ / 100.0).toJSArray, // Chart.js works in major units; tooltip/axis re-attach the currency
        backgroundColor = colorFor(idx, s.category),
        borderRadius = 3,
        borderWidth = 0,
      )
    }.toJSArray

    // Tooltip: "Groceries: 1,234.56 PLN" per stacked segment.
    val labelCb: js.Function1[js.Dynamic, String] = (item: js.Dynamic) => {
      val v    = item.parsed.y.asInstanceOf[Double]
      val name = item.dataset.label.asInstanceOf[String]
      f"$name: $v%,.2f $cur"
    }
    // Y-axis ticks: compact ("1.2k").
    val tickCb: js.Function1[js.Any, String]      = (value: js.Any) => compactMajor(value.asInstanceOf[Double])

    js.Dynamic.literal(
      `type` = "bar",
      data = js.Dynamic.literal(labels = labels, datasets = datasets),
      options = js.Dynamic.literal(
        responsive = true,
        maintainAspectRatio = false,
        // Tooltip shows only the single segment directly under the cursor (not the whole month's stack).
        interaction = js.Dynamic.literal(mode = "nearest", intersect = true),
        scales = js.Dynamic.literal(
          x = js.Dynamic.literal(stacked = true, grid = js.Dynamic.literal(display = false)),
          y = js.Dynamic.literal(stacked = true, ticks = js.Dynamic.literal(callback = tickCb)),
        ),
        plugins = js.Dynamic.literal(
          legend = js.Dynamic.literal(position = "bottom", labels = js.Dynamic.literal(boxWidth = 12, usePointStyle = true)),
          tooltip = js.Dynamic.literal(callbacks = js.Dynamic.literal(label = labelCb)),
        ),
      ),
    )
  }

  // ---- Top uncategorized counterparties -----------------------------------------------------

  private def uncategorizedCard(data: AnalyticsResponse): HtmlElement =
    div(
      cls := "card mb-4",
      div(cls := "card-header", "Top uncategorized counterparties"),
      div(
        cls   := "card-body p-0",
        if data.topUncategorized.isEmpty then div(cls := "text-muted text-center py-4", "Nothing to triage — every transaction has a category. 🎉")
        else
          table(
            cls                                       := "table table-sm table-hover mb-0",
            thead(
              tr(
                th(cls := "ps-3", "Counterparty"),
                th(cls := "text-end", "Transactions"),
                th(cls := "text-end pe-3", "Outflow"),
              ),
            ),
            tbody(
              data.topUncategorized.map { c =>
                tr(
                  td(cls := "ps-3", c.name),
                  td(cls := "text-end", c.count.toString),
                  td(cls := "text-end pe-3", MoneyFormatter.formatSimple(c.outflowCents, data.currency)),
                )
              },
            ),
          ),
      ),
      div(cls := "card-footer text-muted small", "Add a rule on the Transactions page to auto-categorize these."),
    )
}
