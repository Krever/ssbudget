package ssbudget.frontend.components

import com.raquo.laminar.api.L.*

/** Building blocks for editing in place inside a table row: the small inputs and the Save/Cancel/Delete button groups that the budget cards' add,
  * edit and pay rows are made of. Kept in one place so every inline editor in the app submits and cancels the same way.
  */
object InlineEdit {

  def textInput(
      defaultVal: String,
      refCallback: org.scalajs.dom.html.Input => Unit,
      placeholderText: String = "",
      autoFocus: Boolean = false,
  ): HtmlElement =
    input(
      cls          := "form-control form-control-sm",
      tpe          := "text",
      defaultValue := defaultVal,
      Option.when(placeholderText.nonEmpty)(placeholder := placeholderText),
      onMountCallback(ctx => refCallback(ctx.thisNode.ref.asInstanceOf[org.scalajs.dom.html.Input])),
      Option.when(autoFocus)(onMountFocus),
    )

  /** Amount input in major units, right-aligned. Read it back with [[parseCents]]. */
  def moneyInput(
      defaultCents: Option[Long],
      refCallback: org.scalajs.dom.html.Input => Unit,
      placeholderText: String = "Amount",
      autoFocus: Boolean = false,
  ): HtmlElement =
    input(
      cls          := "form-control form-control-sm text-end",
      tpe          := "number",
      stepAttr     := "0.01",
      placeholder  := placeholderText,
      defaultValue := defaultCents.map(c => (c / 100.0).toString).getOrElse(""),
      onMountCallback(ctx => refCallback(ctx.thisNode.ref.asInstanceOf[org.scalajs.dom.html.Input])),
      Option.when(autoFocus)(onMountFocus),
    )

  /** Cents from a [[moneyInput]] — 0 for anything unparseable (including a not-yet-mounted, and therefore null, ref). */
  def parseCents(input: org.scalajs.dom.html.Input): Long =
    Option(input).flatMap(_.value.toDoubleOption).map(d => (d * 100).toLong).getOrElse(0L)
}
