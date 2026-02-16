package ssbudget.frontend.pages

import com.raquo.laminar.api.L.*
import ssbudget.frontend.components.Loading
import ssbudget.frontend.services.DataService
import ssbudget.frontend.util.{Formatting, MoneyFormatter}
import ssbudget.shared.model.*

import java.time.Instant
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

object OneTimeExpensesPage {

  private val dataService = DataService.instance

  private val editingId = Var[Option[OneTimeExpenseId]](None)
  private val adding    = Var(false)

  def apply(): HtmlElement = {
    div(
      cls := "container-fluid mt-3",
      h4("One-Time Expenses"),
      div(
        cls := "card",
        div(
          cls := "card-header py-2 d-flex justify-content-between align-items-center",
          span("All One-Time Expenses"),
          button(cls := "btn btn-sm btn-outline-primary", "+ Add", onClick --> { _ => adding.set(true) }),
        ),
        div(
          cls := "card-body p-0",
          table(
            cls := "table table-sm table-hover mb-0",
            thead(tr(th("Date"), th("Name"), th(cls := "text-end", "Amount"), th("Cur"), th("Actions"))),
            tbody(
              child <-- adding.signal
                .combineWith(dataService.primaryCurrency)
                .combineWith(dataService.enabledCurrencies)
                .map { case (isAdding, primary, currencies) =>
                  if isAdding then addRow(primary, currencies)
                  else emptyNode
                },
              children <-- dataService.oneTimeExpenses
                .combineWith(editingId.signal)
                .map { case (expenses, editId) =>
                  expenses.sortBy(_.date)(Ordering[Instant].reverse).map { expense =>
                    if editId.contains(expense.id) then editRow(expense)
                    else expenseRow(expense)
                  }
                },
            ),
          ),
        ),
      ),
    )
  }

  private def expenseRow(expense: OneTimeExpense): HtmlElement = {
    tr(
      td(cls := "text-muted", Formatting.formatDate(expense.date)),
      td(expense.name),
      td(cls := "text-end font-monospace", MoneyFormatter.format(expense.amountCents, expense.currency)),
      td(span(cls := "badge text-bg-success", expense.currency.code)),
      td(
        div(
          cls := "btn-group btn-group-sm",
          button(cls := "btn btn-outline-secondary btn-sm", "Edit", onClick --> { _ => editingId.set(Some(expense.id)) }),
          Loading.actionButton("Del", () => dataService.deleteOneTimeExpense(expense.id), "btn btn-outline-danger btn-sm"),
        ),
      ),
    )
  }

  private def editRow(expense: OneTimeExpense): HtmlElement = {
    var nameRef: org.scalajs.dom.html.Input   = null
    var amountRef: org.scalajs.dom.html.Input = null
    var dateRef: org.scalajs.dom.html.Input   = null
    val currencyValue                         = Var(expense.currency)

    tr(
      cls := "table-warning",
      td(
        input(
          cls          := "form-control form-control-sm",
          tpe          := "date",
          defaultValue := Formatting.formatIso(expense.date),
          onMountCallback(ctx => dateRef = ctx.thisNode.ref.asInstanceOf[org.scalajs.dom.html.Input]),
        ),
      ),
      td(
        input(
          cls          := "form-control form-control-sm",
          tpe          := "text",
          defaultValue := expense.name,
          onMountCallback(ctx => nameRef = ctx.thisNode.ref.asInstanceOf[org.scalajs.dom.html.Input]),
        ),
      ),
      td(
        input(
          cls          := "form-control form-control-sm text-end",
          tpe          := "number",
          stepAttr     := "0.01",
          defaultValue := (expense.amountCents / 100.0).toString,
          onMountCallback(ctx => amountRef = ctx.thisNode.ref.asInstanceOf[org.scalajs.dom.html.Input]),
        ),
      ),
      td(
        child <-- dataService.enabledCurrencies.map { currencies =>
          select(
            cls := "form-select form-select-sm",
            currencies.map { curr =>
              option(value := curr.code, selected := (curr == expense.currency), curr.code)
            },
            onChange.mapToValue --> { v => currencyValue.set(Currency(v)) },
          )
        },
      ),
      td(
        div(
          cls := "btn-group btn-group-sm",
          Loading.actionButton(
            "Save",
            () => {
              val name        = Option(nameRef).map(_.value.trim).getOrElse("")
              val amountCents = Option(amountRef).flatMap(_.value.toDoubleOption).map(d => (d * 100).toLong).getOrElse(0L)
              val dateStr     = Option(dateRef).map(_.value.trim).getOrElse("")
              val date        = parseDate(dateStr).getOrElse(expense.date)
              if name.nonEmpty then {
                dataService.updateOneTimeExpense(expense.id, name, amountCents, currencyValue.now(), date).map(_ => editingId.set(None))
              } else Future.successful(())
            },
            "btn btn-primary btn-sm",
          ),
          button(tpe := "button", cls := "btn btn-secondary btn-sm", "Cancel", onClick --> { _ => editingId.set(None) }),
        ),
      ),
    )
  }

  private def addRow(primaryCurrency: Currency, currencies: List[Currency]): HtmlElement = {
    var nameRef: org.scalajs.dom.html.Input   = null
    var amountRef: org.scalajs.dom.html.Input = null
    var dateRef: org.scalajs.dom.html.Input   = null
    val currencyValue                         = Var(primaryCurrency)

    val addAction = Loading.actionGroup(
      "Add",
      () => {
        val name        = Option(nameRef).map(_.value.trim).getOrElse("")
        val amountCents = Option(amountRef).flatMap(_.value.toDoubleOption).map(d => (d * 100).toLong).getOrElse(0L)
        val dateStr     = Option(dateRef).map(_.value.trim).getOrElse("")
        val date        = parseDate(dateStr)
        if name.nonEmpty && amountCents > 0 then {
          dataService.addOneTimeExpense(name, amountCents, currencyValue.now(), date).map(_ => adding.set(false))
        } else Future.successful(())
      },
      "btn btn-success btn-sm",
    )

    tr(
      cls := "table-primary",
      td(
        input(
          cls          := "form-control form-control-sm",
          tpe          := "date",
          defaultValue := Formatting.formatIsoToday,
          onMountCallback(ctx => dateRef = ctx.thisNode.ref.asInstanceOf[org.scalajs.dom.html.Input]),
        ),
      ),
      td(
        input(
          cls         := "form-control form-control-sm",
          tpe         := "text",
          placeholder := "Expense name",
          onMountCallback(ctx => nameRef = ctx.thisNode.ref.asInstanceOf[org.scalajs.dom.html.Input]),
          onMountFocus,
          addAction.onEnter,
        ),
      ),
      td(
        input(
          cls         := "form-control form-control-sm text-end",
          tpe         := "number",
          stepAttr    := "0.01",
          placeholder := "Amount",
          onMountCallback(ctx => amountRef = ctx.thisNode.ref.asInstanceOf[org.scalajs.dom.html.Input]),
          addAction.onEnter,
        ),
      ),
      td(
        select(
          cls := "form-select form-select-sm",
          currencies.map(curr => option(value := curr.code, selected := (curr == primaryCurrency), curr.code)),
          onChange.mapToValue --> { v => currencyValue.set(Currency(v)) },
        ),
      ),
      td(
        div(
          cls := "btn-group btn-group-sm",
          addAction.btn,
          button(tpe := "button", cls := "btn btn-secondary btn-sm", "Cancel", onClick --> { _ => adding.set(false) }),
        ),
      ),
    )
  }

  private def parseDate(dateStr: String): Option[Instant] = {
    if dateStr.isEmpty then None
    else {
      scala.util.Try {
        java.time.LocalDate.parse(dateStr).atStartOfDay(java.time.ZoneId.of("UTC")).toInstant
      }.toOption
    }
  }
}
