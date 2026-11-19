package ssbudget.frontend.components

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L.*
import com.raquo.laminar.codecs.StringAsIsCodec
import com.raquo.laminar.tags.HtmlTag
import org.scalajs.dom
import ssbudget.frontend.util.Formatting

import scala.scalajs.js

/** What each chart of the app is made of, so that all charts are drawn and read in one way.
  *
  * [[chart]] is the one entry point for a full chart. It owns the mechanism, and a chart gives it only what is its own: the series, how a pointer
  * position maps to a position of the data, and the text. The rules it holds:
  *   - A plot is drawn at the real width of its container, in pixels. A stretched plot makes ovals of its marks and cannot hold text.
  *   - One vertical scale, with a few round ticks, hairline rules and labels in muted ink ([[Scale]]).
  *   - A line is 2 pixels with round joins; a line of less weight, and a line that is expected and not measured, are styles of it ([[LineStyle]]).
  *   - A mark has a ring in the colour of the surface it is on, which keeps it apart from the line it sits on.
  *   - The pointer finds a position, it does not have to hit a mark: a vertical rule goes to the nearest position, and one readout gives all figures
  *     of it. The arrow keys do the same when the plot has the focus.
  *   - Each figure of a readout is also in a table below the plot, so nothing can be read only with a pointer.
  *   - Text is in the inks of text, not in the colour of a series. A short stroke in the colour of the series ([[lineKey]]) names the series.
  *
  * [[spark]] is the one chart with no scale and no readout: a line at the height of a table row, for the shape only.
  */
object ChartKit {

  // The series colours are from a palette that was checked for colour-blind separation and for contrast on a light surface.
  val seriesInk = "#2a78d6"
  val secondInk = "#eb6834"
  val thirdInk  = "#7b5ea7"
  val mutedInk  = "#6c757d"

  private val gridInk = "#e9ecef"
  private val zeroInk = "#adb5bd"

  /** What a chart is on. The ring of a mark has this colour, so a chart must say which it is. */
  val whiteSurface = "#ffffff" // a card
  val lightSurface = "#f8f9fa" // Bootstrap's `bg-light` and `table-light`

  // The room around the plot area: the scale labels on the left, the position labels below.
  private val padLeft   = 44.0
  private val padRight  = 10.0
  private val padTop    = 10.0
  private val padBottom = 20.0

  /** The least distance between two labels below the plot, in pixels. */
  private val labelRoom = 64.0

  private val bandOpacity = 0.08

  private val sansFace = "font-family: var(--bs-font-sans-serif); font-variant-numeric: tabular-nums"

  // --- the plot area -------------------------------------------------------------------------------

  /** A plot of `plotWidth` by `plotHeight` pixels on `surface`, and the parts that are drawn with its size. */
  final case class Area(plotWidth: Double, plotHeight: Double, surface: String) {

    /** The pixel at `fraction` of the way from the left edge of the plot area to its right edge. */
    def across(fraction: Double): Double = padLeft + fraction * (plotWidth - padLeft - padRight)

    /** The inverse of [[across]]: how far across the plot area the pixel `at` is. Outside the area it is below 0 or above 1. */
    def fractionAt(at: Double): Double = (at - padLeft) / math.max(plotWidth - padLeft - padRight, 1.0)

    /** The rules of the scale and their labels. Solid hairlines: a dash is kept for a line that has a meaning, such as a projection or a level. */
    def scaleRules(axis: Scale): Seq[SvgElement] = {
      import com.raquo.laminar.api.L.svg.*
      axis.ticks.map { tick =>
        g(
          line(
            x1          := padLeft.toString,
            x2          := (plotWidth - padRight).toString,
            y1          := axis.py(tick).toString,
            y2          := axis.py(tick).toString,
            stroke      := (if tick == 0 then zeroInk else gridInk),
            strokeWidth := "1",
          ),
          text(
            x           := (padLeft - 6).toString,
            y           := axis.py(tick).toString,
            dy          := "0.32em",
            textAnchor  := "end",
            fontSize    := "11",
            fill        := mutedInk,
            axis.label(tick),
          ),
        )
      }
    }

    /** The labels below the plot, from `candidates` (a label and where it would be, in pixels, left to right).
      *
      * The first and the last are always drawn: the first starts at its position and the last ends at its position, so that neither goes out of the
      * plot. One between them is drawn, with its middle at its position, when it has room from the label before it and from the last one. A label
      * next to the first or the last needs more room than two middle labels need. One label alone is put with its middle at its position.
      */
    def positionLabels(candidates: Vector[(String, Double)]): Seq[SvgElement] = {
      import com.raquo.laminar.api.L.svg.*

      def label(of: (String, Double), anchor: String): SvgElement =
        text(x := of._2.toString, y := (plotHeight - 5).toString, textAnchor := anchor, fontSize := "11", fill := mutedInk, of._1)

      // Fewer than two candidates have no "first" and "last" to keep apart.
      if candidates.size < 2 then candidates.map(label(_, "middle"))
      else {
        val first     = candidates.head
        val last      = candidates.last
        val between   = candidates.slice(1, candidates.size - 1)
        val nearAnEnd = labelRoom * 1.3
        val kept      = between.foldLeft((Vector.empty[(String, Double)], first._2, nearAnEnd)) { case ((kept, keptAt, room), candidate) =>
          if candidate._2 - keptAt >= room && last._2 - candidate._2 >= nearAnEnd then (kept :+ candidate, candidate._2, labelRoom)
          else (kept, keptAt, room)
        }
        (label(first, "start") +: kept._1.map(label(_, "middle"))) :+ label(last, "end")
      }
    }

    /** A shaded band from `fromX` to `toX` pixels, over the full height of the plot area and not out of it: a range that the chart talks about. */
    def band(fromX: Double, toX: Double, ink: String): SvgElement = {
      import com.raquo.laminar.api.L.svg.*
      val (start, end)       = (math.max(padLeft, fromX), math.min(plotWidth - padRight, toX))
      rect(
        x       := start.toString,
        y       := padTop.toString,
        width   := math.max(0.0, end - start).toString,
        height  := (plotHeight - padTop - padBottom).toString,
        fill    := ink,
        opacity := bandOpacity.toString,
      )
    }

    /** A dashed hairline across the plot at `atY`: a level that the series is read against, such as an expected figure. */
    def level(atY: Double): SvgElement = {
      import com.raquo.laminar.api.L.svg.*
      line(
        x1              := padLeft.toString,
        x2              := (plotWidth - padRight).toString,
        y1              := atY.toString,
        y2              := atY.toString,
        stroke          := mutedInk,
        strokeWidth     := "1",
        strokeDashArray := "4 3",
      )
    }

    /** A mark on a line: filled, or hollow for a figure that is expected and not recorded. One group, so that `more` (a text for the mark, an
      * opacity, a name for a test) applies to the mark as one thing.
      */
    def mark(atX: Double, atY: Double, ink: String, hollow: Boolean = false)(more: Modifier[SvgElement]*): SvgElement = {
      import com.raquo.laminar.api.L.svg.*
      g(
        more,
        circle(cx     := atX.toString, cy := atY.toString, r := "6", fill := surface),
        circle(
          cx          := atX.toString,
          cy          := atY.toString,
          r           := (if hollow then "3.5" else "4"),
          fill        := (if hollow then surface else ink),
          stroke      := ink,
          strokeWidth := (if hollow then "2" else "0"),
        ),
      )
    }

    /** The vertical rule that marks the position under the pointer. */
    private[ChartKit] def pointerRule(at: Double): SvgElement = {
      import com.raquo.laminar.api.L.svg.*
      line(
        x1          := at.toString,
        x2          := at.toString,
        y1          := padTop.toString,
        y2          := (plotHeight - padBottom).toString,
        stroke      := zeroInk,
        strokeWidth := "1",
      )
    }
  }

  /** A name on a mark that is a point of the data (`data-point`), for a test to find it by. */
  val pointName: SvgAttr[String] = L.svg.svgAttr("data-point", StringAsIsCodec, None)

  // --- the scale -----------------------------------------------------------------------------------

  /** A vertical scale in cents for a plot of `plotHeight` pixels: its ticks, lowest first, and where a value is drawn. */
  final case class Scale(ticks: Vector[Long], lo: Double, hi: Double, plotHeight: Double) {

    def py(cents: Long): Double = padTop + (hi - cents) / math.max(hi - lo, 1.0) * (plotHeight - padTop - padBottom)

    /** One style for all labels of the scale: thousands as "K" when the scale reaches 10,000, the full figure when it does not. */
    private val compact = ticks.exists(t => math.abs(t) >= 1000000)

    def label(cents: Long): String = {
      val units = cents / 100.0
      if !compact then Formatting.formatMoneyShort(cents)
      else if units == 0 then "0"
      else if units % 1000 == 0 then f"${units / 1000}%.0fK"
      else f"${units / 1000}%.1fK"
    }
  }

  object Scale {

    /** A scale that holds all `values`: a few round ticks. A chart that reads its heights from zero puts zero in `values`. A tick a little outside
      * the values is kept, so that the highest and the lowest value have a labelled rule near them; a tick far outside would only add an empty band.
      */
    def of(values: Iterable[Long], plotHeight: Double): Scale = {
      val (min, max) = (values.min / 100.0, values.max / 100.0)
      val range      = math.max(max - min, 1.0)
      val raw        = range / 4
      val power      = math.pow(10, math.floor(math.log10(raw)))
      val unit       = raw / power
      val step       = (if unit <= 1 then 1.0 else if unit <= 2 then 2.0 else if unit <= 5 then 5.0 else 10.0) * power
      val from       = math.ceil((min - range / 10) / step).toLong
      val to         = math.floor((max + range / 10) / step).toLong
      val ticks      = (from to to).map(i => math.round(i * step * 100)).toVector
      Scale(ticks, math.min(values.min, ticks.head).toDouble, math.max(values.max, ticks.last).toDouble, plotHeight)
    }
  }

  // --- lines ---------------------------------------------------------------------------------------

  /** How a line is drawn. The weights and the dash are here, so that a chart selects a meaning and not a number. */
  enum LineStyle(val weight: String, val dash: Option[String]) {

    /** The series the chart is about. */
    case Main extends LineStyle("2", None)

    /** A series that gives context to the main one. */
    case Secondary extends LineStyle("1.5", None)

    /** The main series where it is expected and not measured. */
    case Projected extends LineStyle("2", Some("5 4"))
  }

  /** A line through `trace` (pixels), in the colour of its series. */
  def seriesLine(trace: Seq[(Double, Double)], ink: String, as: LineStyle = LineStyle.Main): SvgElement = {
    import com.raquo.laminar.api.L.svg.*
    path(
      d              := s"M${trace.map { case (atX, atY) => s"$atX,$atY" }.mkString(" L")}",
      fill           := "none",
      stroke         := ink,
      strokeWidth    := as.weight,
      strokeLineJoin := "round",
      strokeLineCap  := "round",
      as.dash.map(strokeDashArray := _),
    )
  }

  /** A light fill between a line and the level `base` (pixels): on which side of that level the series is, at a glance. */
  def wash(trace: Seq[(Double, Double)], base: Double, ink: String): SvgElement = {
    import com.raquo.laminar.api.L.svg.*
    path(
      d       := s"M${trace.head._1},$base L${trace.map { case (atX, atY) => s"$atX,$atY" }.mkString(" L")} L${trace.last._1},$base Z",
      fill    := ink,
      opacity := "0.1",
    )
  }

  // --- the legend and the readout --------------------------------------------------------------------

  /** A short stroke in the colour of a series: the key that names a line in a legend and in a readout. */
  def lineKey(ink: String, dashed: Boolean = false): HtmlElement =
    span(
      cls       := "d-inline-block align-middle me-1",
      styleAttr := s"width: 14px; border-top: 2px ${if dashed then "dashed" else "solid"} $ink",
    )

  /** A small block in the colour of a [[Area.band]]: the key that names it. It is stronger than the band, which is faint over a full plot and would
    * not be seen at this size.
    */
  def bandKey(ink: String): HtmlElement =
    span(cls := "d-inline-block align-middle me-1", styleAttr := s"width: 12px; height: 10px; background: $ink; opacity: ${bandOpacity * 3}")

  /** One entry of a legend: a key and what it names. `hint` is shown when the pointer rests on the entry, for a series that needs a sentence. */
  def legendKey(key: HtmlElement, label: String, hint: String = ""): HtmlElement =
    span(Option.when(hint.nonEmpty)(title := hint), key, label)

  /** One line of a readout: what the figure is, and the figure. The figure that the reader came for is `strong`. */
  def readoutRow(key: Option[HtmlElement], label: String, value: String, strong: Boolean = false): HtmlElement =
    div(
      cls := "d-flex justify-content-between column-gap-3",
      span(cls := "text-muted", key, label),
      span(cls := (if strong then "fw-semibold" else ""), value),
    )

  /** What a chart says about the position under the pointer.
    *
    * @param at
    *   where the position is, in pixels from the left: the rule goes there
    * @param marks
    *   a mark on each line at that position
    * @param heading
    *   what the position is, such as a day
    * @param rows
    *   the figures of the position, from [[readoutRow]]
    */
  final case class Focus(at: Double, marks: Seq[FocusMark], heading: String, rows: Seq[HtmlElement])
  final case class FocusMark(atY: Double, ink: String, hollow: Boolean = false)

  // --- a full chart ----------------------------------------------------------------------------------

  /** A chart: the plot, its legend and its table.
    *
    * @param id
    *   the name of the chart for a test (`data-chart`)
    * @param label
    *   what the chart shows, for a reader that cannot see it
    * @param surface
    *   what the chart is on: [[whiteSurface]] or [[lightSurface]]
    * @param nearest
    *   what a pointer at this many pixels from the left is on
    * @param step
    *   the position one to the left (-1) or to the right (1) of a position; with no start, the position that the arrow keys begin at
    * @param draw
    *   the plot: all that does not change with the pointer
    * @param focus
    *   what to mark and to say for a position
    * @param legend
    *   the entries of the legend, from [[legendKey]]
    * @param result
    *   the result of the chart in words, at the right of the legend
    * @param tableRows
    *   the figures of the plot, one row for each position; they are made only when the table is opened
    */
  def chart[H](id: String, plotHeight: Double, label: String, surface: String)(
      nearest: (Double, Area) => H,
      step: (Option[H], Int) => H,
      draw: Area => Seq[SvgElement],
      focus: (H, Area) => Focus,
      legend: Seq[HtmlElement],
      result: String,
      tableHead: List[String],
      tableRows: => Seq[List[String]],
  ): HtmlElement = {
    val width = Var(0.0)
    val hover = Var(Option.empty[H])

    // Before the element is measured its width is zero, and there is no plot. A pointer that moves inside one position gives the same position
    // many times: `distinct`, so that only a new position builds a new readout.
    def areaAt(w: Double): Option[Area] = Option.when(w > padLeft + padRight + 20)(Area(w, plotHeight, surface))
    val area                            = width.signal.distinct.map(areaAt)
    val hovered                         = hover.signal.distinct

    def plot(in: Area): SvgElement =
      L.svg.svg(
        L.svg.width  := in.plotWidth.toString,
        L.svg.height := in.plotHeight.toString,
        draw(in),
        // The position under the pointer: a rule from top to bottom, and a mark on each line.
        children <-- hovered.map(_.toList.flatMap { position =>
          val found = focus(position, in)
          in.pointerRule(found.at) +: found.marks.map(m => in.mark(found.at, m.atY, m.ink, m.hollow)())
        }),
      )

    /** The figures of the position under the pointer, on the side of the rule where there is more room, so that they do not cover the position they
      * describe.
      */
    def readout(found: Focus, in: Area): HtmlElement = {
      val side = if found.at > in.plotWidth / 2 then s"right: ${in.plotWidth - found.at + 12}px" else s"left: ${found.at + 12}px"
      div(
        dataAttr("chart-readout") := found.heading,
        cls                       := "position-absolute bg-white border rounded shadow-sm px-2 py-1 small",
        styleAttr                 := s"top: 4px; $side; min-width: 11rem; pointer-events: none; z-index: 5; line-height: 1.35",
        div(cls := "text-muted", found.heading),
        found.rows,
      )
    }

    div(
      dataAttr("chart") := id,
      // The cards of the pages set a fixed-width face for their columns of figures. A chart is not a column: it uses the face of the page.
      styleAttr         := sansFace,
      div(
        cls        := "position-relative",
        styleAttr  := s"height: ${plotHeight.toInt}px; outline-offset: 2px",
        tabIndex   := 0,
        role       := "img",
        aria.label := label,
        // The width of the plot is the width of this element, and it follows the element when the page is resized.
        onMountUnmountCallbackWithState[HtmlElement, dom.ResizeObserver](
          mount = { ctx =>
            val el       = ctx.thisNode.ref
            val observer = new dom.ResizeObserver((_, _) => width.set(el.clientWidth.toDouble))
            width.set(el.clientWidth.toDouble)
            observer.observe(el)
            observer
          },
          unmount = (_, observer) => observer.foreach(_.disconnect()),
        ),
        onMouseMove --> { ev =>
          val at = ev.clientX - ev.currentTarget.asInstanceOf[dom.html.Element].getBoundingClientRect().left
          hover.set(areaAt(width.now()).map(nearest(at, _)))
        },
        onMouseLeave --> (_ => hover.set(None)),
        onBlur --> (_ => hover.set(None)),
        onKeyDown --> { ev =>
          ev.key match {
            case "ArrowLeft"  =>
              ev.preventDefault()
              hover.update(from => Some(step(from, -1)))
            case "ArrowRight" =>
              ev.preventDefault()
              hover.update(from => Some(step(from, 1)))
            case "Escape"     => hover.set(None)
            case _            => ()
          }
        },
        child <-- area.map(_.fold(emptyNode)(plot)),
        child.maybe <-- hovered.combineWith(area).map { case (position, in) => position.zip(in).map { case (p, a) => readout(focus(p, a), a) } },
      ),
      div(
        cls        := "d-flex flex-wrap justify-content-between column-gap-3 small text-muted mt-1",
        div(cls := "d-flex flex-wrap column-gap-3", legend),
        span(result),
      ),
      tableView(tableHead, tableRows),
    )
  }

  /** The index, in `positions` (pixels, left to right), of the position nearest to `at`. */
  def nearestIndex(positions: Seq[Double], at: Double): Int = positions.indices.minBy(i => math.abs(positions(i) - at))

  /** The figures of a plot as a table, closed until it is asked for, and made only then. The first column is text; the others are figures and are set
    * to the right.
    */
  private def tableView(head: List[String], rows: => Seq[List[String]]): HtmlElement = {
    val details  = htmlTag("details")
    val summary  = htmlTag("summary")
    val onToggle = eventProp[dom.Event]("toggle")
    val opened   = Var(false)

    def cells(cell: HtmlTag[dom.html.TableCell], texts: List[String]): List[HtmlElement] =
      texts.zipWithIndex.map { case (text, i) => cell(Option.when(i > 0)(cls := "text-end"), text) }

    details(
      cls := "small",
      onToggle --> (ev => opened.set(ev.target.asInstanceOf[js.Dynamic].open.asInstanceOf[Boolean])),
      summary(cls := "text-muted", styleAttr := "cursor: pointer; width: fit-content", "Table"),
      child <-- opened.signal.distinct.map {
        case false => emptyNode
        case true  => L.table(cls := "table table-sm small mb-0 w-auto", thead(tr(cells(th, head))), tbody(rows.map(row => tr(cells(td, row)))))
      },
    )
  }

  // --- the line of a table row -----------------------------------------------------------------------

  /** A line at the height of a table row: 96 by 18 pixels, drawn at that size, with zero in view and a small mark on the last position. It shows the
    * shape of a series; the figures are in the cells next to it.
    *
    * @param series
    *   each position as how far across it is (0 to 1) and its value in cents, left to right
    */
  def spark(series: Vector[(Double, Long)], label: String): SvgElement = {
    import com.raquo.laminar.api.L.svg.*

    val (wide, tall, pad) = (96.0, 18.0, 3.0)
    val values            = series.map(_._2) :+ 0L
    val (lo, hi)          = (values.min.toDouble, values.max.toDouble)

    def py(cents: Long): Double = pad + (hi - cents) / math.max(hi - lo, 1.0) * (tall - 2 * pad)
    val trace                   = series.map { case (fraction, cents) => (pad + fraction * (wide - 2 * pad), py(cents)) }

    svg(
      L.svg.width  := wide.toInt.toString,
      L.svg.height := tall.toInt.toString,
      titleTag(label),
      line(x1   := "0", x2                    := wide.toString, y1         := py(0).toString, y2 := py(0).toString, stroke := gridInk, strokeWidth := "1"),
      seriesLine(trace, seriesInk, LineStyle.Secondary),
      circle(cx := trace.last._1.toString, cy := trace.last._2.toString, r := "2", fill          := seriesInk),
    )
  }
}
