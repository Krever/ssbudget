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
      refCallback: org.scalajs.dom.html.Input => Unit = _ => (),
      placeholderText: String = "Amount",
      autoFocus: Boolean = false,
  ): HtmlElement =
    input(
      cls          := "form-control form-control-sm text-end",
      tpe          := "number",
      stepAttr     := "0.01",
      placeholder  := placeholderText,
      defaultValue := defaultCents.map(centsText).getOrElse(""),
      onMountCallback(ctx => refCallback(ctx.thisNode.ref.asInstanceOf[org.scalajs.dom.html.Input])),
      Option.when(autoFocus)(onMountFocus),
    )

  /** Cents as the text a [[moneyInput]] shows (major units). The inverse of [[parseCentsOpt]]. */
  def centsText(cents: Long): String = (cents / 100.0).toString

  /** Blurring is what commits an inline cell, so Enter just leaves the field rather than duplicating the save. */
  val commitOnEnter: Modifier[HtmlElement] =
    onKeyDown.filter(_.key == "Enter") --> { ev => ev.target.asInstanceOf[org.scalajs.dom.html.Input].blur() }

  /** Cents from a [[moneyInput]] — 0 for anything unparseable, including a not-yet-mounted (and therefore null) ref. */
  def parseCents(input: org.scalajs.dom.html.Input): Long =
    Option(input).flatMap(i => parseCentsOpt(i.value)).getOrElse(0L)

  /** Cents from an amount typed in major units, comma or dot. The one parsing rule; [[parseCents]] is this plus a default. It differs only in
    * answering a different question — `None` keeps "nothing was typed" distinct from zero, which is what an optional amount (a card limit, a fixed
    * budget figure) needs.
    */
  def parseCentsOpt(text: String): Option[Long] =
    scala.util.Try((BigDecimal(text.trim.replace(",", ".")) * 100).setScale(0, BigDecimal.RoundingMode.HALF_UP).toLongExact).toOption

  /** A control with its name in front of it. One owner: the settings strip and the rule modal draw the same shape, and an e2e locator depends on it.
    */
  def labelled(name: String, controls: Modifier[HtmlElement]*): HtmlElement =
    div(cls := "d-flex align-items-center gap-2", span(cls := "small text-muted", name), controls)
}
