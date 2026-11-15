package ssbudget.frontend.components

import com.raquo.laminar.api.L.*
import ssbudget.frontend.components.ChartKit.*
import ssbudget.frontend.util.MoneyFormatter
import ssbudget.shared.model.{Currency, MonthlySpend}

/** A line chart of a monthly money series: is this steady or spiky, and does the slice we're averaging cover the representative months?
  *
  * One series. The months a statistic counted are a shaded band behind the line, not a second line colour, so the window reads as context and visibly
  * narrows the moment it changes. The figure the series produced is a dashed level across the plot: it is a level, not a measurement. Values are
  * magnitudes: pass the sign a category's money flows and an income series reads upward just like an expense one.
  *
  * It is a [[ChartKit.chart]], drawn by the rules that are stated there. A month is a discrete position and there are few of them, so this chart has
  * a mark for each month; a month that the statistic did not count is dimmed.
  */
object MonthlyHistoryChart {

  private val plotHeight = 124.0

  /** @param series
    *   the months, oldest first, each already flagged with whether the statistic counted it
    * @param sign
    *   `1` when the money is spent, `-1` when it arrives; values are multiplied by it so the chart always reads upward
    * @param reference
    *   a magnitude to rule across the plot — the figure the series produced
    * @param name
    *   what the series is: the name of its line in the legend, and part of the chart's accessible name
    */
  def apply(series: List[MonthlySpend], sign: Long, reference: Long, currency: Currency, name: String): HtmlElement =
    if series.isEmpty then div(cls := "small text-muted fst-italic", "No completed months on record yet.")
    else {
      val model = Model(series.toVector, sign, reference)
      chart[Int](
        "monthly-history",
        plotHeight,
        s"$name: ${series.size} months of history, ${series.head.month} to ${series.last.month}. The figures are in the table below.",
        // It is shown in the settings strip of a category, which is a light row of the table.
        lightSurface,
      )(
        nearest = (at, in) => math.max(0, math.min(model.last, math.round(in.fractionAt(at) * model.last).toInt)),
        step = (from, by) => math.max(0, math.min(model.last, from.fold(model.last)(_ + by))),
        draw = draw(model, _),
        focus = (index, in) => {
          val month = model.months(index)
          Focus(
            in.across(model.fraction(index)),
            List(FocusMark(model.scale.py(model.values(index)), seriesInk)),
            month.month,
            List(
              readoutRow(Some(lineKey(seriesInk)), "Amount", MoneyFormatter.formatSimple(model.values(index), currency), strong = true),
              readoutRow(Some(lineKey(mutedInk, dashed = true)), "Expected", MoneyFormatter.formatBare(reference)),
              readoutRow(None, if month.included then "In the figure" else "Not in the figure", ""),
            ),
          )
        },
        legend = List(
          legendKey(lineKey(seriesInk), name),
          legendKey(bandKey(seriesInk), "months the figure uses"),
          legendKey(lineKey(mutedInk, dashed = true), "expected"),
        ),
        result = MoneyFormatter.formatSimple(reference, currency),
        tableHead = List("Month", "Amount", "In the figure"),
        tableRows = model.months.indices.map { i =>
          List(model.months(i).month, MoneyFormatter.formatBare(model.values(i)), if model.months(i).included then "yes" else "")
        },
      )
    }

  /** The series of one chart and its scales. */
  final private case class Model(months: Vector[MonthlySpend], sign: Long, reference: Long) {
    val values: Vector[Long] = months.map(_.cents * sign)
    val last: Int            = months.size - 1

    // Zero and the expected figure are always part of the scale: the heights have a meaning, and the level is in view.
    val scale: Scale = Scale.of(values :+ 0L :+ reference, plotHeight)

    /** How far across the plot month `i` is, 0 to 1. The months are evenly spaced; one month alone is put in the middle. */
    def fraction(i: Int): Double = if last == 0 then 0.5 else i.toDouble / last
  }

  private def draw(model: Model, in: Area): Seq[SvgElement] = {
    def at(i: Int): (Double, Double) = (in.across(model.fraction(i)), model.scale.py(model.values(i)))

    val included = model.months.indices.filter(model.months(_).included)
    // Half the distance between two months: the band goes that far past its first and its last month.
    val halfStep = if model.last == 0 then 12.0 else (in.across(model.fraction(1)) - in.across(model.fraction(0))) / 2

    List[Seq[SvgElement]](
      // What the statistic looked at.
      included.headOption.map(first => in.band(at(first)._1 - halfStep, at(included.last)._1 + halfStep, seriesInk)).toList,
      in.scaleRules(model.scale),
      in.positionLabels(model.months.indices.toVector.map(i => model.months(i).month -> in.across(model.fraction(i)))),
      // The figure the series produced, across the history it came from.
      List(in.level(model.scale.py(model.reference))),
      Option.when(model.last > 0)(seriesLine(model.months.indices.map(at), seriesInk)).toList,
      // A mark for each month, with its own text for a reader that cannot use the pointer readout. `data-point` is the name a test finds it by.
      model.months.indices.map { i =>
        val (monthX, monthY)   = at(i)
        in.mark(monthX, monthY, seriesInk)(
          pointName   := model.months(i).month,
          svg.opacity := (if model.months(i).included then "1" else "0.35"),
          svg.titleTag(s"${model.months(i).month}: ${MoneyFormatter.formatBare(model.values(i))}"),
        )
      },
    ).flatten
  }
}
