package ssbudget.frontend.components

import com.raquo.laminar.api.L.*
import ssbudget.frontend.util.{Formatting, MoneyFormatter}
import ssbudget.shared.model.{Currency, FreeMoneyPoint, FreeMoneyProjection, FreeMoneyTrend}

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** A line chart of free money across one period, with an optional projection to the period's expected end.
  *
  * The line is [[FreeMoneyTrend.anchored]]: free money with the savings transfers and the unplanned income taken out, ending on the real figure of
  * the last day. Thus the line shows only what the plan lost, and the dashed projection continues it: the same series, the same colour, and the dash
  * says that it is not measured. The zero rule is always in view, because the important question is on which side of zero the line ends.
  *
  * A second, thinner line in another colour is [[FreeMoneyTrend.projectionHistory]]: the projected end from each day, at the usual daily change. It
  * is the only line with a legend, below the plot. It rises across days that were better than the usual day, and ends level with the projection's end
  * dot.
  *
  * The other visual rules are those of [[Sparkline]]: labels in HTML below the plot, and a readout on each dot.
  */
object FreeMoneyChart {

  private val seriesColour = "#0d6efd"
  private val forecastInk  = "#fd7e14"
  private val gridInk      = "#adb5bd"

  private val w    = 480.0
  private val edge = 4.0 // keeps the end dots off the viewBox border

  /** The chart with its caption. `None` when there are fewer than two days to draw: one point is not a trend. */
  def apply(
      points: List[FreeMoneyPoint],
      projection: Option[FreeMoneyProjection],
      forecasts: List[(LocalDate, Long)],
      currency: Currency,
  ): Option[HtmlElement] =
    Option.when(points.sizeIs >= 2) {
      val (first, last) = (points.head, points.last)
      val endDay        = projection.fold(last.day)(_.endDay)
      val result        = projection match {
        case Some(p) => s"${MoneyFormatter.formatSigned(p.perDayCents)} / day · projected ${MoneyFormatter.formatSimple(p.endCents, currency)}"
        case None    =>
          s"${MoneyFormatter.formatSigned(FreeMoneyTrend.leakCents(points))} against the plan in ${ChronoUnit.DAYS.between(first.day, last.day)} days"
      }
      div(
        dataAttr("chart") := "free-money",
        plot(FreeMoneyTrend.anchored(points).toVector, projection, forecasts.toVector, plotHeight = 96.0, dots = true),
        // The labels are HTML, outside the plot: the plot stretches to the width of its container, and text inside it would stretch too.
        div(
          cls := "d-flex justify-content-between small text-muted font-sans-serif",
          span(s"${Formatting.formatDay(first.day)} – ${Formatting.formatDay(endDay)}"),
          span(result),
        ),
        // One point is not a line, and a legend for a line that is not there is noise.
        Option.when(forecasts.sizeIs >= 2) {
          div(
            cls := "small text-muted font-sans-serif",
            span(styleAttr := s"color: $forecastInk", "━ "),
            "projected end from each day, at the usual pace",
          )
        },
      )
    }

  /** The line only, at the height of a table row. It shows the shape of a period; the figures are in the cells next to it. */
  def mini(points: List[FreeMoneyPoint]): Option[HtmlElement] =
    Option.when(points.sizeIs >= 2) {
      span(
        cls       := "d-inline-block align-middle",
        styleAttr := "width: 96px",
        plot(FreeMoneyTrend.anchored(points).toVector, None, Vector.empty, plotHeight = 18.0, dots = false),
      )
    }

  /** The plot, split out because `L.svg.*` shadows the HTML names (`cls`, `line`, `x`, `y`) used above. */
  private def plot(
      days: Vector[(LocalDate, Long)],
      projection: Option[FreeMoneyProjection],
      forecasts: Vector[(LocalDate, Long)],
      plotHeight: Double,
      dots: Boolean,
  ): SvgElement = {
    import com.raquo.laminar.api.L.svg.*

    // Keeps strokes at their stated width although the viewBox is stretched to the width of the container.
    val vectorEffect = svgAttr("vector-effect", com.raquo.laminar.codecs.StringAsIsCodec, None)
    val pad          = if dots then 6.0 else 2.0

    val firstDay             = days.head._1
    val (lastDay, lastCents) = days.last
    val daySpan              = math.max(1L, ChronoUnit.DAYS.between(firstDay, projection.fold(lastDay)(_.endDay))).toDouble

    // Zero is always part of the range: a line that goes below it is the thing to see.
    val values   = days.map(_._2) ++ forecasts.map(_._2) ++ projection.map(_.endCents) :+ 0L
    val (lo, hi) = (values.min.toDouble, values.max.toDouble)
    val range    = if hi == lo then 1.0 else hi - lo

    def px(day: LocalDate): Double = edge + ChronoUnit.DAYS.between(firstDay, day) / daySpan * (w - 2 * edge)
    def py(cents: Long): Double    = pad + (hi - cents) / range * (plotHeight - 2 * pad)

    svg(
      width               := "100%",
      height              := plotHeight.toInt.toString,
      viewBox             := s"0 0 ${w.toInt} ${plotHeight.toInt}",
      preserveAspectRatio := "none",
      titleTag(s"Free money, ${days.size} days"),
      line(
        x1           := "0",
        y1           := py(0).toString,
        x2           := w.toString,
        y2           := py(0).toString,
        stroke       := gridInk,
        strokeWidth  := "1",
        vectorEffect := "non-scaling-stroke",
      ),
      // Below the main line in the paint order: where the two cross, free money stays on top.
      Option.when(forecasts.sizeIs >= 2) {
        path(
          d            := forecasts.indices.map(i => s"${if i == 0 then "M" else "L"}${px(forecasts(i)._1)},${py(forecasts(i)._2)}").mkString(" "),
          fill         := "none",
          stroke       := forecastInk,
          strokeWidth  := "1.5",
          vectorEffect := "non-scaling-stroke",
        )
      },
      path(
        d            := days.indices.map(i => s"${if i == 0 then "M" else "L"}${px(days(i)._1)},${py(days(i)._2)}").mkString(" "),
        fill         := "none",
        stroke       := seriesColour,
        strokeWidth  := (if dots then "2" else "1.5"),
        vectorEffect := "non-scaling-stroke",
      ),
      projection.map { p =>
        List(
          line(
            x1              := px(lastDay).toString,
            y1              := py(lastCents).toString,
            x2              := px(p.endDay).toString,
            y2              := py(p.endCents).toString,
            stroke          := seriesColour,
            strokeWidth     := "2",
            strokeDashArray := "4 4",
            vectorEffect    := "non-scaling-stroke",
          ),
          // Hollow: this point is expected, not recorded.
          circle(
            cx              := px(p.endDay).toString,
            cy              := py(p.endCents).toString,
            r               := "3",
            fill            := "#fff",
            stroke          := seriesColour,
            strokeWidth     := "2",
            vectorEffect    := "non-scaling-stroke",
            titleTag(s"${Formatting.formatDay(p.endDay)}: ${MoneyFormatter.formatBare(p.endCents)} (projected)"),
          ),
        )
      },
      Option.when(dots && forecasts.sizeIs >= 2) {
        forecasts.map { case (day, cents) =>
          circle(
            cx   := px(day).toString,
            cy   := py(cents).toString,
            r    := "2",
            fill := forecastInk,
            titleTag(s"${Formatting.formatDay(day)}: projected end ${MoneyFormatter.formatBare(cents)}"),
          )
        }
      },
      // One dot for each day, each with its own readout. This is the hover layer, and it needs no JavaScript.
      Option.when(dots) {
        days.map { case (day, cents) =>
          circle(
            cx   := px(day).toString,
            cy   := py(cents).toString,
            r    := "2.5",
            fill := seriesColour,
            titleTag(s"${Formatting.formatDay(day)}: ${MoneyFormatter.formatBare(cents)}"),
          )
        }
      },
    )
  }
}
