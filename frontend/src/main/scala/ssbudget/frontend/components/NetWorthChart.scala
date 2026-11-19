package ssbudget.frontend.components

import com.raquo.laminar.api.L.*
import ssbudget.frontend.components.ChartKit.*
import ssbudget.frontend.util.{Formatting, MoneyFormatter}
import ssbudget.shared.api.PeriodSummary

/** What was owned at the end of each period: the sum of all accounts, and the two parts of it that are not for spending.
  *
  * A position is the end of a period; the open period ends "now". The net worth is the series the chart is about. Savings and investments are lines
  * of context below it: the first shows what was put aside, the second moves with the market too. The spending balance is not a line of its own,
  * because at the end of a period it is what is left before the paycheck, and it says little; it is in the readout and in the table, as is the money
  * on hold.
  *
  * It is a [[ChartKit.chart]], drawn by the rules that are stated there. A period is a discrete position and there are few of them, so the net worth
  * has a mark for each period.
  */
object NetWorthChart {

  private val plotHeight = 150.0

  /** @param summaries
    *   the periods, in any order; a period with no balance on record at its end is left out
    */
  def apply(summaries: List[PeriodSummary]): HtmlElement = {
    val points = summaries.sortBy(_.period.startDate.toEpochMilli).flatMap(Point.of).toVector
    if points.size < 2 then div(cls := "small text-muted fst-italic", "A chart needs the end of two periods or more.")
    else {
      val model                      = Model(points)
      val currency                   = summaries.head.currency
      def money(cents: Long): String = MoneyFormatter.formatSimple(cents, currency)
      chart[Int](
        "net-worth",
        plotHeight,
        s"Net worth at the end of ${points.size} periods, ${points.head.label} to ${points.last.label}. The figures are in the table below.",
        whiteSurface,
      )(
        nearest = (at, in) => math.max(0, math.min(model.last, math.round(in.fractionAt(at) * model.last).toInt)),
        step = (from, by) => math.max(0, math.min(model.last, from.fold(model.last)(_ + by))),
        draw = draw(model, _),
        focus = (index, in) => {
          val p = model.points(index)
          Focus(
            in.across(model.fraction(index)),
            List(
              FocusMark(model.scale.py(p.netWorth), seriesInk),
              FocusMark(model.scale.py(p.savings), secondInk),
              FocusMark(model.scale.py(p.investments), thirdInk),
            ),
            p.label,
            List(
              readoutRow(Some(lineKey(seriesInk)), "Net worth", money(p.netWorth), strong = true),
              readoutRow(Some(lineKey(secondInk)), "Savings", MoneyFormatter.formatBare(p.savings)),
              readoutRow(Some(lineKey(thirdInk)), "Investments", MoneyFormatter.formatBare(p.investments)),
              readoutRow(None, "Spending", MoneyFormatter.formatBare(p.spending)),
              readoutRow(None, "On hold", MoneyFormatter.formatBare(p.onHold)),
            ),
          )
        },
        legend = List(
          legendKey(lineKey(seriesInk), "net worth", "The balance of all accounts at the end of the period, at today's exchange rates"),
          legendKey(lineKey(secondInk), "savings"),
          legendKey(lineKey(thirdInk), "investments", "At their value at the end of the period: this line moves with the market too"),
        ),
        result = money(points.last.netWorth),
        tableHead = List("End of period", "Spending", "On hold", "Savings", "Investments", "Net worth"),
        tableRows = model.points.map(p => p.label :: List(p.spending, p.onHold, p.savings, p.investments, p.netWorth).map(MoneyFormatter.formatBare)),
      )
    }
  }

  /** The end of one period, in the primary currency. */
  /** `label` names the end of the period in full, for the readout and the table. `axis` is the short form under the plot, where a full date of each
    * period has no room.
    */
  final private case class Point(label: String, axis: String, spending: Long, onHold: Long, savings: Long, investments: Long) {
    val netWorth: Long = spending + onHold + savings + investments
  }

  private object Point {

    /** None when no account has a balance on record at the end of the period: there is nothing to draw, and zero would be a false figure. */
    def of(s: PeriodSummary): Option[Point] = {
      val parts = List(s.endBalanceCents, s.holdingEndCents, s.savingsEndCents, s.investmentEndCents)
      Option.when(parts.exists(_.isDefined)) {
        Point(
          s.period.endDate.fold("now")(Formatting.formatDate),
          s.period.endDate.fold("now")(Formatting.formatDateShort),
          s.endBalanceCents.getOrElse(0L),
          s.holdingEndCents.getOrElse(0L),
          s.savingsEndCents.getOrElse(0L),
          s.investmentEndCents.getOrElse(0L),
        )
      }
    }
  }

  /** The series of one chart and its scale. */
  final private case class Model(points: Vector[Point]) {
    val last: Int = points.size - 1

    // Zero is always part of the scale: the heights have a meaning.
    val scale: Scale = Scale.of(points.flatMap(p => List(p.netWorth, p.savings, p.investments)) :+ 0L, plotHeight)

    /** How far across the plot period `i` is, 0 to 1. The periods are evenly spaced: the chart is about their ends, not about their lengths. */
    def fraction(i: Int): Double = if last == 0 then 0.5 else i.toDouble / last
  }

  private def draw(model: Model, in: Area): Seq[SvgElement] = {
    def trace(value: Point => Long): Seq[(Double, Double)] =
      model.points.indices.map(i => (in.across(model.fraction(i)), model.scale.py(value(model.points(i)))))

    List[Seq[SvgElement]](
      in.scaleRules(model.scale),
      in.positionLabels(model.points.indices.toVector.map(i => model.points(i).axis -> in.across(model.fraction(i)))),
      List(
        seriesLine(trace(_.savings), secondInk, LineStyle.Secondary),
        seriesLine(trace(_.investments), thirdInk, LineStyle.Secondary),
        seriesLine(trace(_.netWorth), seriesInk),
      ),
      // A mark for each period on the net worth, with its own text for a reader that cannot use the pointer readout.
      model.points.indices.map { i =>
        val p      = model.points(i)
        val (x, y) = (in.across(model.fraction(i)), model.scale.py(p.netWorth))
        in.mark(x, y, seriesInk)(pointName := p.label, svg.titleTag(s"${p.label}: ${MoneyFormatter.formatBare(p.netWorth)}"))
      },
    ).flatten
  }
}
