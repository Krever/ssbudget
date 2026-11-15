package ssbudget.frontend.components

import com.raquo.laminar.api.L.*
import ssbudget.frontend.components.ChartKit.*
import ssbudget.frontend.util.{Formatting, MoneyFormatter}
import ssbudget.shared.model.{Currency, FreeMoneyPoint, FreeMoneyProjection, FreeMoneyTrend}

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** A line chart of free money across one period, with an optional projection to the period's expected end.
  *
  * The main line is [[FreeMoneyTrend.anchored]]: free money with the savings transfers and the unplanned income taken out, ending on the real figure
  * of the last day. Thus the line shows only what the plan lost. The dashed projection continues it: the same series, the same colour, and the dash
  * says that it is not measured. Zero is always in view, because the important question is on which side of zero the line ends.
  *
  * A second line in another colour is [[FreeMoneyTrend.projectionHistory]]: the projected end from each day, at the usual daily change. It rises
  * across days that were better than the usual day, and ends level with the projection's end mark.
  *
  * It is a [[ChartKit.chart]], drawn by the rules that are stated there. There is no mark for each day: the pointer finds the day, and the readout of
  * a day of the open period also names the causes of its change.
  */
object FreeMoneyChart {

  private val plotHeight = 156.0

  /** What the pointer is on: a recorded day, or the projected end. */
  private enum Hover {
    case Day(index: Int)
    case End(projection: FreeMoneyProjection)
  }

  /** The chart with its legend and its table. `None` when there are fewer than two days to draw: one point is not a trend.
    *
    * @param surface
    *   what the chart is on: [[ChartKit.whiteSurface]] or [[ChartKit.lightSurface]]
    */
  def apply(
      points: List[FreeMoneyPoint],
      projection: Option[FreeMoneyProjection],
      forecasts: List[(LocalDate, Long)],
      currency: Currency,
      surface: String,
  ): Option[HtmlElement] =
    Option.when(points.sizeIs >= 2) {
      val model = Model(points, projection, forecasts.toMap)
      chart[Hover](
        "free-money",
        plotHeight,
        s"Free money from ${Formatting.formatDay(model.firstDay)} to ${Formatting.formatDay(model.endDay)}. The figures are in the table below.",
        surface,
      )(
        nearest = model.nearest,
        step = model.step,
        draw = draw(model, _),
        focus = focus(model, _, _, currency),
        legend = List(
          Some(legendKey(lineKey(seriesInk), "Free money")),
          Option.when(projection.isDefined)(legendKey(lineKey(seriesInk, dashed = true), "projected")),
          // One point is not a line, and a legend for a line that is not there is noise.
          Option.when(forecasts.sizeIs >= 2) {
            legendKey(
              lineKey(secondInk),
              "Projected end, from each day",
              hint = "Where the period would end, seen from each day, if the remaining days were usual days. It rises when days go better than usual.",
            )
          },
        ).flatten,
        result = projection match {
          case Some(p) => s"${MoneyFormatter.formatSigned(p.perDayCents)} / day · projected ${MoneyFormatter.formatSimple(p.endCents, currency)}"
          case None    =>
            val days = ChronoUnit.DAYS.between(model.firstDay, model.lastDay)
            s"${MoneyFormatter.formatSigned(FreeMoneyTrend.leakCents(points))} against the plan in $days days"
        },
        tableHead = List("Day", "Free money", "Change", "Projected end"),
        tableRows = model.line.indices.map { i =>
          val (day, cents) = model.line(i)
          List(
            Formatting.formatDay(day),
            MoneyFormatter.formatBare(cents),
            model.change(i).fold("")(MoneyFormatter.formatSigned),
            model.forecasts.get(day).fold("")(MoneyFormatter.formatBare),
          )
        } ++ projection.map(p => List(s"${Formatting.formatDay(p.endDay)} (projected)", MoneyFormatter.formatBare(p.endCents), "", "")),
      )
    }

  /** The line only, at the height of a table row. It shows the shape of a period; the figures are in the cells next to it. */
  def mini(points: List[FreeMoneyPoint]): Option[HtmlElement] =
    Option.when(points.sizeIs >= 2) {
      val line  = FreeMoneyTrend.anchored(points).toVector
      val first = line.head._1
      val span0 = math.max(1L, ChronoUnit.DAYS.between(first, line.last._1)).toDouble
      span(
        cls := "d-inline-block align-middle",
        spark(line.map { case (day, cents) => (ChronoUnit.DAYS.between(first, day) / span0, cents) }, s"Free money, ${line.size} days"),
      )
    }

  // --- the data of one chart ---------------------------------------------------------------------

  /** The series of one chart and its scales. */
  final private case class Model(points: List[FreeMoneyPoint], projection: Option[FreeMoneyProjection], forecasts: Map[LocalDate, Long]) {

    /** The line that is drawn, one entry for each point and in the order of the points. */
    val line: Vector[(LocalDate, Long)] = FreeMoneyTrend.anchored(points).toVector
    private val byIndex                 = points.toVector

    val firstDay: LocalDate = line.head._1
    val lastDay: LocalDate  = line.last._1
    val endDay: LocalDate   = projection.fold(lastDay)(_.endDay)

    private val daySpan = math.max(1L, ChronoUnit.DAYS.between(firstDay, endDay)).toDouble

    /** How far across the plot a day is, 0 to 1. */
    def fraction(day: LocalDate): Double = ChronoUnit.DAYS.between(firstDay, day) / daySpan

    // The same for each recorded day, computed once: a pointer move reads these and does no date arithmetic.
    private val fractions = line.map { case (day, _) => fraction(day) }

    // Zero is always part of the scale: a line that goes below it is the thing to see.
    val scale: Scale = Scale.of(line.map(_._2) ++ forecasts.values ++ projection.map(_.endCents) :+ 0L, plotHeight)

    def causes(index: Int): List[ssbudget.shared.model.LineCause] = byIndex(index).causes

    /** The day labels: the first day, each seventh day after it, and the last day. The chart draws those that have room. */
    def dayLabels(in: Area): Vector[(String, Double)] =
      (Iterator.iterate(firstDay)(_.plusDays(7)).takeWhile(_.isBefore(endDay)).toVector :+ endDay)
        .map(day => Formatting.formatDay(day) -> in.across(fraction(day)))

    /** The change of the line from the day before, when the day before has a point. */
    def change(index: Int): Option[Long] =
      Option.when(index > 0 && line(index - 1)._1 == line(index)._1.minusDays(1))(line(index)._2 - line(index - 1)._2)

    /** What a pointer at `at` is on: the nearest recorded day, or the projected end when the pointer is nearer to that. */
    def nearest(at: Double, in: Area): Hover = {
      val across = in.fractionAt(at)
      val index  = fractions.indices.minBy(i => math.abs(fractions(i) - across))
      projection match {
        case Some(p) if math.abs(1.0 - across) < math.abs(fractions(index) - across) => Hover.End(p)
        case _                                                                       => Hover.Day(index)
      }
    }

    /** One day to the left or to the right of `from`. With no start, the last recorded day. */
    def step(from: Option[Hover], by: Int): Hover = {
      val last = line.size - 1
      (from, projection) match {
        case (None, _)                                      => Hover.Day(last)
        case (Some(end: Hover.End), _)                      => if by < 0 then Hover.Day(last) else end
        case (Some(Hover.Day(i)), Some(p)) if i + by > last => Hover.End(p)
        case (Some(Hover.Day(i)), _)                        => Hover.Day(math.max(0, math.min(last, i + by)))
      }
    }
  }

  // --- the plot and the readout --------------------------------------------------------------------

  private def draw(model: Model, in: Area): Seq[SvgElement] = {
    def at(day: LocalDate, cents: Long): (Double, Double) = (in.across(model.fraction(day)), model.scale.py(cents))

    val mainLine     = model.line.map { case (day, cents) => at(day, cents) }
    val forecastLine = model.line.flatMap { case (day, _) => model.forecasts.get(day).map(at(day, _)) }
    val end          = model.projection.map(p => at(p.endDay, p.endCents))

    List[Seq[SvgElement]](
      in.scaleRules(model.scale),
      in.positionLabels(model.dayLabels(in)),
      List(wash(mainLine, model.scale.py(0), seriesInk)),
      Option.when(forecastLine.sizeIs >= 2)(seriesLine(forecastLine, secondInk, LineStyle.Secondary)).toList,
      end.map(to => seriesLine(List(mainLine.last, to), seriesInk, LineStyle.Projected)).toList,
      List(seriesLine(mainLine, seriesInk), in.mark(mainLine.last._1, mainLine.last._2, seriesInk)()),
      end.map { case (endX, endY) => in.mark(endX, endY, seriesInk, hollow = true)() }.toList,
    ).flatten
  }

  /** What to mark and to say for the day under the pointer: the value first, then its change and what caused it. */
  private def focus(model: Model, hover: Hover, in: Area, currency: Currency): Focus =
    hover match {
      case Hover.End(p)     =>
        Focus(
          in.across(model.fraction(p.endDay)),
          List(FocusMark(model.scale.py(p.endCents), seriesInk, hollow = true)),
          Formatting.formatDay(p.endDay),
          List(
            readoutRow(Some(lineKey(seriesInk, dashed = true)), "Projected", MoneyFormatter.formatSimple(p.endCents, currency), strong = true),
            readoutRow(None, "Usual day", MoneyFormatter.formatSigned(p.perDayCents)),
          ),
        )
      case Hover.Day(index) =>
        val (day, cents) = model.line(index)
        val forecast     = model.forecasts.get(day)
        val causes       = model.causes(index)
        val shown        = 4 // more than these, and the readout is a table, not a hint
        val figures      = List(
          Some(readoutRow(Some(lineKey(seriesInk)), "Free money", MoneyFormatter.formatSimple(cents, currency), strong = true)),
          forecast.map(f => readoutRow(Some(lineKey(secondInk)), "Projected end", MoneyFormatter.formatBare(f))),
          model.change(index).map(c => readoutRow(None, "Change", MoneyFormatter.formatSigned(c), strong = true)),
        ).flatten
        val reasons      = causes.take(shown).map(c => readoutRow(None, c.label, MoneyFormatter.formatSigned(c.cents)))
        val others       = Option.when(causes.sizeIs > shown) {
          readoutRow(None, "others", MoneyFormatter.formatSigned(causes.drop(shown).map(_.cents).sum))
        }
        Focus(
          in.across(model.fraction(day)),
          forecast.map(f => FocusMark(model.scale.py(f), secondInk)).toList :+ FocusMark(model.scale.py(cents), seriesInk),
          Formatting.formatDay(day),
          figures ++ reasons ++ others,
        )
    }
}
