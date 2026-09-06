package ssbudget.frontend.components

import com.raquo.laminar.api.L.*
import ssbudget.frontend.util.MoneyFormatter
import ssbudget.shared.model.{Currency, MonthlySpend}

/** A small line chart of a monthly money series, for reading shape rather than values: is this steady or spiky, and does the slice we're averaging
  * cover the representative months?
  *
  * One series, so no legend — whatever hosts it names it. The months a statistic counted are shown as a shaded background band rather than a second
  * line colour, so identity never rests on colour alone, and every point carries its own readout on hover. Values are magnitudes: pass the sign a
  * category's money flows and an income series reads upward just like an expense one.
  */
object Sparkline {

  /** The one data colour, and the muted ink the reference rule wears. Validated against the card surface: a single series needs no palette, and
    * everything else drawn here is deliberately not a series.
    */
  private val seriesColour = "#0d6efd"
  private val mutedInk     = "#6c757d"
  private val gridInk      = "#dee2e6"

  private val (w, h)              = (480.0, 64.0)
  private val (padTop, padBottom) = (6.0, 6.0)
  private val edge                = 3.0 // keeps the end dots and the band's edge off the viewBox border
  private val baseline            = h - padBottom

  /** @param series
    *   the months, oldest first, each already flagged with whether the statistic counted it
    * @param sign
    *   `1` when the money is spent, `-1` when it arrives; values are multiplied by it so the chart always reads upward
    * @param reference
    *   a magnitude to rule across the plot — the figure the series produced
    * @param name
    *   what the series is, for the chart's accessible title
    */
  def monthly(series: List[MonthlySpend], sign: Long, reference: Long, currency: Currency, name: String): HtmlElement =
    if series.isEmpty then div(cls := "small text-muted fst-italic", "No completed months on record yet.")
    else
      div(
        plot(series.toVector, sign, reference, name),
        // The labels live out here, in HTML: the plot is stretched to its container's width, which would stretch text drawn inside it too.
        div(
          cls := "d-flex justify-content-between small text-muted",
          span(s"${series.head.month} – ${series.last.month}"),
          span(s"⋯ expected ${MoneyFormatter.formatSimple(reference, currency)}"),
        ),
      )

  /** The plot, split out because `L.svg.*` shadows the HTML names (`cls`, `line`, `x`, `y`) used above. */
  private def plot(series: Vector[MonthlySpend], sign: Long, reference: Long, name: String): SvgElement = {
    import com.raquo.laminar.api.L.svg.*

    // Keeps strokes their stated width even though the viewBox is stretched to the container's width.
    val vectorEffect = svgAttr("vector-effect", com.raquo.laminar.codecs.StringAsIsCodec, None)

    val values = series.map(_.cents * sign)
    val top    = math.max(values.max, reference).max(1L).toDouble * 1.15

    def px(i: Int): Double  = if series.sizeIs <= 1 then w / 2 else i * (w - 2 * edge) / (series.size - 1) + edge
    def py(v: Long): Double = baseline - (v.toDouble / top) * (baseline - padTop)

    val included = series.indices.filter(series(_).included)

    svg(
      width               := "100%",
      height              := h.toInt.toString,
      viewBox             := s"0 0 ${w.toInt} ${h.toInt}",
      preserveAspectRatio := "none",
      titleTag(s"$name: ${series.size} months of history"),
      // What the statistic looked at. A background band, not a second line colour, so the window reads as context rather than as a rival series —
      // and it visibly narrows the moment the window changes.
      included.headOption.map { first =>
        rect(
          x       := (px(first) - edge).toString,
          y       := "0",
          width   := (px(included.last) - px(first) + 2 * edge).toString,
          height  := baseline.toString,
          fill    := seriesColour,
          opacity := "0.08",
        )
      },
      // Recessive zero line, so the heights mean something.
      rule(baseline, gridInk, dashed = false, vectorEffect),
      // The figure the series produced, ruled across the history it came from. Labelled in the caption, not here.
      rule(py(reference), mutedInk, dashed = true, vectorEffect),
      path(
        d            := series.indices.map(i => s"${if i == 0 then "M" else "L"}${px(i)},${py(values(i))}").mkString(" "),
        fill         := "none",
        stroke       := seriesColour,
        strokeWidth  := "2",
        vectorEffect := "non-scaling-stroke",
      ),
      // A dot per month, each carrying its own readout — the hover layer, without a line of JavaScript. Uncounted months are dimmed.
      series.indices.map { i =>
        circle(
          cx      := px(i).toString,
          cy      := py(values(i)).toString,
          r       := "2.5",
          fill    := seriesColour,
          opacity := (if series(i).included then "1" else "0.3"),
          titleTag(s"${series(i).month}: ${MoneyFormatter.formatBare(values(i))}"),
        )
      },
    )
  }

  private def rule(atY: Double, ink: String, dashed: Boolean, vectorEffect: SvgAttr[String]): SvgElement = {
    import com.raquo.laminar.api.L.svg.*
    line(
      x1           := "0",
      y1           := atY.toString,
      x2           := w.toString,
      y2           := atY.toString,
      stroke       := ink,
      strokeWidth  := "1",
      vectorEffect := "non-scaling-stroke",
      Option.when(dashed)(strokeDashArray := "3 3"),
    )
  }
}
