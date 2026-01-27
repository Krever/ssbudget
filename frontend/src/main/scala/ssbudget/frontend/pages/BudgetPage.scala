package ssbudget.frontend.pages

import com.raquo.laminar.api.L.*
import ssbudget.frontend.services.DataService
import ssbudget.shared.model.{BudgetItemDefinition, BudgetItemType, ExpenseDefId, ExpenseRecord, Money}

object BudgetPage {

  private val dataService = DataService.instance

  private val editingItemId   = Var[Option[ExpenseDefId]](None)
  private val payingItemId    = Var[Option[ExpenseDefId]](None)
  private val addingPlanned   = Var(false)
  private val addingEstimated = Var(false)
  private val addingIncome    = Var(false)

  def apply(): HtmlElement = {
    div(
      cls := "container-fluid mt-3",
      h4("Budget"),
      div(
        cls := "row g-3 mb-3",
        div(cls := "col-lg-6", plannedItemsCard()),
        div(cls := "col-lg-6", estimatedExpensesCard()),
      ),
    )
  }

  private def plannedItemsCard(): HtmlElement = {
    div(
      cls := "card",
      div(
        cls := "card-header py-2 d-flex justify-content-between align-items-center",
        span("Planned Items"),
        div(
          cls := "btn-group btn-group-sm",
          button(cls := "btn btn-outline-primary", "+ Expense", onClick --> { _ => addingPlanned.set(true) }),
          button(cls := "btn btn-outline-success", "+ Income", onClick --> { _ => addingIncome.set(true) }),
        ),
      ),
      div(
        cls := "card-body p-0",
        table(
          cls := "table table-sm table-hover mb-0",
          thead(
            tr(th("Name"), th(cls := "text-end", "Expected"), th(cls := "text-end", "Actual"), th(cls := "text-center", "Status"), th("Actions")),
          ),
          tbody(
            children <-- dataService.plannedExpenses
              .combineWith(dataService.currentPeriodRecords)
              .combineWith(payingItemId.signal)
              .combineWith(editingItemId.signal)
              .map { case (items, records, payingId, editingId) =>
                items.map(item => plannedItemRow(item, records, payingId, editingId, isIncome = false))
              },
            child <-- addingPlanned.signal.map {
              case true  => addItemRow(BudgetItemType.PlannedExpense, addingPlanned, columns = 5)
              case false => emptyNode
            },
            tr(cls := "table-secondary", td(colSpan := 5, cls := "py-1 small text-muted", "— Incomes —")),
            children <-- dataService.plannedIncomes
              .combineWith(dataService.currentPeriodRecords)
              .combineWith(payingItemId.signal)
              .combineWith(editingItemId.signal)
              .map { case (items, records, payingId, editingId) =>
                items.map(item => plannedItemRow(item, records, payingId, editingId, isIncome = true))
              },
            child <-- addingIncome.signal.map {
              case true  => addItemRow(BudgetItemType.PlannedIncome, addingIncome, columns = 5)
              case false => emptyNode
            },
          ),
        ),
      ),
      div(
        cls := "card-footer py-2",
        div(
          cls := "d-flex justify-content-between mb-1",
          span(cls := "text-muted small", "Unpaid Expenses"),
          span(cls := "font-monospace small", child.text <-- dataService.unpaidPlannedExpenses.map(_.formatted)),
        ),
        div(
          cls := "d-flex justify-content-between",
          span(cls := "text-muted small", "Pending Income"),
          span(cls := "font-monospace small", child.text <-- dataService.pendingIncome.map(_.formatted)),
        ),
      ),
    )
  }

  private def estimatedExpensesCard(): HtmlElement = {
    div(
      cls := "card",
      div(
        cls := "card-header py-2 d-flex justify-content-between align-items-center",
        span("Estimated Expenses"),
        button(cls := "btn btn-sm btn-outline-primary", "+ Add", onClick --> { _ => addingEstimated.set(true) }),
      ),
      div(
        cls := "card-body p-0",
        table(
          cls := "table table-sm table-hover mb-0",
          thead(tr(th("Name"), th(cls := "text-end", "Monthly Est."), th(cls := "text-end", "Scaled"), th("Actions"))),
          tbody(
            children <-- dataService.estimatedExpenses
              .combineWith(dataService.daysRemainingInPeriod)
              .combineWith(editingItemId.signal)
              .map { case (items, daysRemaining, editingId) =>
                val scaleFactor = daysRemaining.toDouble / 30.0
                items.map(item => estimatedItemRow(item, scaleFactor, editingId))
              },
            child <-- addingEstimated.signal.map {
              case true  => addItemRow(BudgetItemType.EstimatedExpense, addingEstimated, columns = 4)
              case false => emptyNode
            },
          ),
        ),
      ),
      div(
        cls := "card-footer py-2 d-flex justify-content-between",
        span("Scaled Total"),
        span(cls := "font-monospace", child.text <-- dataService.scaledEstimatedExpenses.map(_.formatted)),
      ),
    )
  }

  private def plannedItemRow(
      item: BudgetItemDefinition,
      records: List[ExpenseRecord],
      payingId: Option[ExpenseDefId],
      editingId: Option[ExpenseDefId],
      isIncome: Boolean,
  ): HtmlElement = {
    val record     = records.find(_.expenseDefId == item.id)
    val paidAmount = record.flatMap(_.paidAmount)
    val isPaid     = paidAmount.isDefined

    if payingId.contains(item.id) then payItemRow(item)
    else if editingId.contains(item.id) then editItemRow(item, columns = 5)
    else {
      val statusLabel = if isPaid then (if isIncome then "Received" else "Paid") else "Pending"
      val actionLabel = if isIncome then "Receive" else "Pay"
      val undoLabel   = if isIncome then "Undo" else "Unpay"
      val statusBadge = if isPaid then "text-bg-success" else "text-bg-secondary"

      tr(
        td(item.name),
        td(cls := "text-end font-monospace", item.fixedEstimate.fold("-")(Money.pln(_).formatted)),
        td(cls := "text-end font-monospace", paidAmount.fold("-")(Money.pln(_).formatted)),
        td(cls := "text-center", span(cls := s"badge $statusBadge", statusLabel)),
        td(
          div(
            cls := "btn-group btn-group-sm",
            if isPaid then List(
              button(cls := "btn btn-outline-secondary btn-sm", "Edit", onClick --> { _ => editingItemId.set(Some(item.id)) }),
              button(cls := "btn btn-outline-warning btn-sm", undoLabel, onClick --> { _ => dataService.unmarkBudgetItemAsPaid(item.id) }),
            )
            else
              List(
                button(cls := "btn btn-outline-success btn-sm", actionLabel, onClick --> { _ => payingItemId.set(Some(item.id)) }),
                button(cls := "btn btn-outline-secondary btn-sm", "Edit", onClick --> { _ => editingItemId.set(Some(item.id)) }),
              ),
          ),
        ),
      )
    }
  }

  private def payItemRow(item: BudgetItemDefinition): HtmlElement = {
    var inputRef: org.scalajs.dom.html.Input = null

    tr(
      cls := "table-info",
      td(item.name),
      td(cls := "text-end font-monospace", item.fixedEstimate.fold("-")(Money.pln(_).formatted)),
      td(moneyInput(item.fixedEstimate, ref => inputRef = ref, autoFocus = true)),
      td(),
      td(
        saveCancel(
          onSave = () => {
            dataService.markBudgetItemAsPaid(item.id, parseCents(inputRef))
            payingItemId.set(None)
          },
          onCancel = () => payingItemId.set(None),
        ),
      ),
    )
  }

  private def estimatedItemRow(item: BudgetItemDefinition, scaleFactor: Double, editingId: Option[ExpenseDefId]): HtmlElement = {
    val monthlyEstimate = item.fixedEstimate.getOrElse(0L)
    val scaledEstimate  = (monthlyEstimate * scaleFactor).toLong

    if editingId.contains(item.id) then editItemRow(item, columns = 4)
    else
      tr(
        td(item.name),
        td(cls := "text-end font-monospace", Money.pln(monthlyEstimate).formatted),
        td(cls := "text-end font-monospace", Money.pln(scaledEstimate).formatted),
        td(button(cls := "btn btn-outline-secondary btn-sm", "Edit", onClick --> { _ => editingItemId.set(Some(item.id)) })),
      )
  }

  private def editItemRow(item: BudgetItemDefinition, columns: Int): HtmlElement = {
    var nameRef: org.scalajs.dom.html.Input     = null
    var estimateRef: org.scalajs.dom.html.Input = null
    val emptyCols                               = columns - 3

    tr(
      cls := "table-warning",
      td(textInput(item.name, ref => nameRef = ref)),
      td(moneyInput(item.fixedEstimate, ref => estimateRef = ref, autoFocus = true)),
      (0 until emptyCols).map(_ => td()),
      td(
        saveCancelDelete(
          onSave = () => {
            dataService.updateBudgetItemEstimate(item.id, parseCents(estimateRef))
            editingItemId.set(None)
          },
          onCancel = () => editingItemId.set(None),
          onDelete = () => {
            dataService.deleteBudgetItem(item.id)
            editingItemId.set(None)
          },
        ),
      ),
    )
  }

  private def addItemRow(itemType: BudgetItemType, addingVar: Var[Boolean], columns: Int): HtmlElement = {
    var nameRef: org.scalajs.dom.html.Input     = null
    var estimateRef: org.scalajs.dom.html.Input = null
    val emptyCols                               = columns - 3
    val isIncome                                = itemType == BudgetItemType.PlannedIncome
    val namePlaceholder                         = if isIncome then "Income name" else "Expense name"
    val amountPlaceholder                       = if isIncome then "Expected" else "Estimate"

    tr(
      cls := "table-primary",
      td(textInput("", ref => nameRef = ref, placeholderText = namePlaceholder, autoFocus = true)),
      td(moneyInput(None, ref => estimateRef = ref, placeholderText = amountPlaceholder)),
      (0 until emptyCols).map(_ => td()),
      td(
        saveCancel(
          onSave = () => {
            val name = Option(nameRef).map(_.value.trim).getOrElse("")
            if name.nonEmpty then {
              dataService.addBudgetItem(name, itemType, parseCents(estimateRef))
              addingVar.set(false)
            }
          },
          onCancel = () => addingVar.set(false),
          saveLabel = "Add",
        ),
      ),
    )
  }

  private def textInput(
      defaultVal: String,
      refCallback: org.scalajs.dom.html.Input => Unit,
      placeholderText: String = "",
      autoFocus: Boolean = false,
  ): HtmlElement = {
    input(
      cls          := "form-control form-control-sm",
      tpe          := "text",
      defaultValue := defaultVal,
      Option.when(placeholderText.nonEmpty)(placeholder := placeholderText),
      onMountCallback(ctx => refCallback(ctx.thisNode.ref.asInstanceOf[org.scalajs.dom.html.Input])),
      Option.when(autoFocus)(onMountFocus),
    )
  }

  private def moneyInput(
      defaultCents: Option[Long],
      refCallback: org.scalajs.dom.html.Input => Unit,
      placeholderText: String = "Amount",
      autoFocus: Boolean = false,
  ): HtmlElement = {
    input(
      cls          := "form-control form-control-sm text-end",
      tpe          := "number",
      stepAttr     := "0.01",
      placeholder  := placeholderText,
      defaultValue := defaultCents.map(c => (c / 100.0).toString).getOrElse(""),
      onMountCallback(ctx => refCallback(ctx.thisNode.ref.asInstanceOf[org.scalajs.dom.html.Input])),
      Option.when(autoFocus)(onMountFocus),
    )
  }

  private def parseCents(input: org.scalajs.dom.html.Input): Long = {
    Option(input).flatMap(_.value.toDoubleOption).map(d => (d * 100).toLong).getOrElse(0L)
  }

  private def saveCancel(onSave: () => Unit, onCancel: () => Unit, saveLabel: String = "Save"): HtmlElement = {
    div(
      cls := "btn-group btn-group-sm",
      button(tpe := "button", cls := "btn btn-success btn-sm", saveLabel, onClick --> { _ => onSave() }),
      button(tpe := "button", cls := "btn btn-secondary btn-sm", "Cancel", onClick --> { _ => onCancel() }),
    )
  }

  private def saveCancelDelete(onSave: () => Unit, onCancel: () => Unit, onDelete: () => Unit): HtmlElement = {
    div(
      cls := "btn-group btn-group-sm",
      button(tpe := "button", cls := "btn btn-primary btn-sm", "Save", onClick --> { _ => onSave() }),
      button(tpe := "button", cls := "btn btn-secondary btn-sm", "Cancel", onClick --> { _ => onCancel() }),
      button(tpe := "button", cls := "btn btn-danger btn-sm", "Del", onClick --> { _ => onDelete() }),
    )
  }
}
