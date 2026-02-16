package ssbudget.frontend.pages

import com.raquo.laminar.api.L.*
import ssbudget.frontend.components.Loading
import ssbudget.frontend.services.DataService
import ssbudget.frontend.util.{Formatting, MoneyFormatter}
import ssbudget.frontend.{Page, Router}
import ssbudget.shared.model.*

import java.time.Instant
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

object BudgetPage {

  private val dataService = DataService.instance

  private val editingItemId   = Var[Option[ExpenseDefId]](None)
  private val payingItemId    = Var[Option[ExpenseDefId]](None)
  private val addingPlanned   = Var(false)
  private val addingEstimated = Var(false)
  private val addingIncome    = Var(false)
  private val showOnlyPending = Var(false)

  // Savings state
  private val savingToAccountId  = Var[Option[SavingsAccountId]](None)
  private val expandedSavingsIds = Var[Set[SavingsAccountId]](Set.empty)

  // One-time expenses state
  private val addingOneTime    = Var(false)
  private val editingOneTimeId = Var[Option[OneTimeExpenseId]](None)

  def apply(): HtmlElement = {
    div(
      cls := "container-fluid mt-3",
      h4("Budget"),
      div(
        cls   := "row g-3 mb-3",
        div(cls := "col-lg-6", plannedItemsCard()),
        div(cls := "col-lg-6", estimatedExpensesCard()),
      ),
      div(cls := "row g-3 mb-3", div(cls := "col-12", plannedSavingsCard())),
      div(cls := "row g-3", div(cls := "col-12", oneTimeExpensesCard())),
    )
  }

  private def plannedItemsCard(): HtmlElement = {
    div(
      cls := "card",
      div(
        cls := "card-header py-2 d-flex justify-content-between align-items-center",
        span("Planned Items"),
        div(
          cls := "d-flex align-items-center gap-2",
          div(
            cls := "form-check form-switch mb-0",
            input(
              cls     := "form-check-input",
              tpe     := "checkbox",
              idAttr  := "showPendingOnly",
              checked <-- showOnlyPending.signal,
              onChange.mapToChecked --> showOnlyPending.writer,
            ),
            label(cls := "form-check-label small", forId := "showPendingOnly", "Pending only"),
          ),
          div(
            cls := "btn-group btn-group-sm",
            button(cls := "btn btn-outline-primary", "+ Expense", onClick --> { _ => addingPlanned.set(true) }),
            button(cls := "btn btn-outline-success", "+ Income", onClick --> { _ => addingIncome.set(true) }),
          ),
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
              .combineWith(showOnlyPending.signal)
              .map { case (items, records, payingId, editingId, pendingOnly) =>
                val filteredItems =
                  if pendingOnly then items.filter(item => !records.exists(r => r.expenseDefId == item.id && r.paidAmount.isDefined))
                  else items
                filteredItems.map(item => plannedItemRow(item, records, payingId, editingId, isIncome = false))
              },
            child <-- addingPlanned.signal.combineWith(dataService.primaryCurrency).map {
              case (true, currency) => addItemRow(BudgetItemType.PlannedExpense, addingPlanned, columns = 5, currency)
              case (false, _)       => emptyNode
            },
            tr(cls := "table-secondary", td(colSpan := 5, cls := "py-1 small text-muted", "— Incomes —")),
            children <-- dataService.plannedIncomes
              .combineWith(dataService.currentPeriodRecords)
              .combineWith(payingItemId.signal)
              .combineWith(editingItemId.signal)
              .combineWith(showOnlyPending.signal)
              .map { case (items, records, payingId, editingId, pendingOnly) =>
                val filteredItems =
                  if pendingOnly then items.filter(item => !records.exists(r => r.expenseDefId == item.id && r.paidAmount.isDefined))
                  else items
                filteredItems.map(item => plannedItemRow(item, records, payingId, editingId, isIncome = true))
              },
            child <-- addingIncome.signal.combineWith(dataService.primaryCurrency).map {
              case (true, currency) => addItemRow(BudgetItemType.PlannedIncome, addingIncome, columns = 5, currency)
              case (false, _)       => emptyNode
            },
          ),
        ),
      ),
      div(
        cls := "card-footer py-2",
        div(
          cls := "d-flex justify-content-between mb-1",
          span(cls := "text-muted small", "Unpaid Expenses"),
          span(cls := "font-monospace small", MoneyFormatter.formatChild(dataService.unpaidPlannedExpenses)),
        ),
        div(
          cls := "d-flex justify-content-between",
          span(cls := "text-muted small", "Pending Income"),
          span(cls := "font-monospace small", MoneyFormatter.formatChild(dataService.pendingIncome)),
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
            child <-- addingEstimated.signal.combineWith(dataService.primaryCurrency).map {
              case (true, currency) => addItemRow(BudgetItemType.EstimatedExpense, addingEstimated, columns = 4, currency)
              case (false, _)       => emptyNode
            },
          ),
        ),
      ),
      div(
        cls := "card-footer py-2 d-flex justify-content-between",
        span("Scaled Total"),
        span(cls := "font-monospace", MoneyFormatter.formatChild(dataService.scaledEstimatedExpenses)),
      ),
    )
  }

  private def plannedSavingsCard(): HtmlElement = {
    div(
      cls := "card",
      div(
        cls := "card-header py-2",
        span("Planned Savings"),
      ),
      div(
        cls := "card-body p-0",
        table(
          cls := "table table-sm table-hover mb-0",
          thead(
            tr(
              th("Account"),
              th(cls := "text-end", "Target"),
              th(cls := "text-end", "Saved"),
              th(cls := "text-end", "Remaining"),
              th(),
            ),
          ),
          tbody(
            children <-- dataService.savingsAccounts
              .combineWith(dataService.currentPeriodSavingsTransactions)
              .combineWith(savingToAccountId.signal)
              .combineWith(expandedSavingsIds.signal)
              .map { case (accounts, txns, savingToId, expandedIds) =>
                // Show accounts with targets first, then accounts without targets
                val (withTargets, withoutTargets) = accounts.partition(_.plannedMonthly.isDefined)
                val sortedAccounts                = withTargets ++ withoutTargets
                sortedAccounts.flatMap { account =>
                  val periodTxns      = txns.filter(_.accountId == account.id)
                  val periodTotal     = periodTxns.map(_.amount).sum
                  val isExpanded      = expandedIds.contains(account.id)
                  val mainRow         = savingsTargetRow(account, periodTotal, periodTxns, savingToId, isExpanded)
                  val suggestedAmount = account.plannedMonthly.map(_ - periodTotal).getOrElse(0L)
                  val txnRows         = if isExpanded then {
                    periodTxns.map(txn => savingsTransactionRow(txn, account.currency)) :+
                      (if savingToId.contains(account.id) then addSavingsTransactionRow(account, suggestedAmount)
                       else addTransactionButton(account))
                  } else Nil
                  mainRow :: txnRows
                }
              },
          ),
        ),
      ),
      div(
        cls := "card-footer py-2 d-flex justify-content-between",
        span("Remaining to Save"),
        span(cls := "font-monospace text-warning", MoneyFormatter.formatChild(dataService.remainingSavingsTarget)),
      ),
    )
  }

  private def oneTimeExpensesCard(): HtmlElement = {
    div(
      cls := "card",
      div(
        cls := "card-header py-2 d-flex justify-content-between align-items-center",
        span("One-Time Expenses"),
        div(
          cls := "d-flex align-items-center gap-2",
          button(cls := "btn btn-sm btn-outline-primary", "+ Add", onClick --> { _ => addingOneTime.set(true) }),
          a(
            cls      := "btn btn-sm btn-outline-secondary",
            href     := Router.absoluteUrlForPage(Page.OneTimeExpenses),
            Router.linkTo(Page.OneTimeExpenses),
            "View All",
          ),
        ),
      ),
      div(
        cls := "card-body p-0",
        table(
          cls := "table table-sm table-hover mb-0",
          thead(tr(th("Date"), th("Name"), th(cls := "text-end", "Amount"), th("Cur"), th("Actions"))),
          tbody(
            child <-- addingOneTime.signal
              .combineWith(dataService.primaryCurrency)
              .combineWith(dataService.enabledCurrencies)
              .map { case (isAdding, primary, currencies) =>
                if isAdding then oneTimeAddRow(primary, currencies)
                else emptyNode
              },
            children <-- dataService.oneTimeExpenses
              .combineWith(dataService.currentPeriod)
              .combineWith(editingOneTimeId.signal)
              .map { case (expenses, periodOpt, editId) =>
                val periodExpenses = periodOpt match {
                  case Some(period) =>
                    expenses.filter { e =>
                      !e.date.isBefore(period.startDate) &&
                      period.endDate.forall(end => e.date.isBefore(end))
                    }
                  case None         => List.empty
                }
                periodExpenses.sortBy(_.date)(Ordering[Instant].reverse).map { expense =>
                  if editId.contains(expense.id) then oneTimeEditRow(expense)
                  else oneTimeRow(expense)
                }
              },
          ),
        ),
      ),
    )
  }

  private def oneTimeRow(expense: OneTimeExpense): HtmlElement = {
    tr(
      td(cls := "text-muted small", Formatting.formatDate(expense.date)),
      td(expense.name),
      td(cls := "text-end font-monospace", MoneyFormatter.format(expense.amountCents, expense.currency)),
      td(span(cls := "badge text-bg-success", expense.currency.code)),
      td(
        div(
          cls := "btn-group btn-group-sm",
          button(cls := "btn btn-outline-secondary btn-sm", "Edit", onClick --> { _ => editingOneTimeId.set(Some(expense.id)) }),
          Loading.actionButton("Del", () => dataService.deleteOneTimeExpense(expense.id), "btn btn-outline-danger btn-sm"),
        ),
      ),
    )
  }

  private def oneTimeEditRow(expense: OneTimeExpense): HtmlElement = {
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
              val date        = parseOneTimeDate(dateStr).getOrElse(expense.date)
              if name.nonEmpty then {
                dataService.updateOneTimeExpense(expense.id, name, amountCents, currencyValue.now(), date).map(_ => editingOneTimeId.set(None))
              } else Future.successful(())
            },
            "btn btn-primary btn-sm",
          ),
          button(tpe := "button", cls := "btn btn-secondary btn-sm", "Cancel", onClick --> { _ => editingOneTimeId.set(None) }),
        ),
      ),
    )
  }

  private def oneTimeAddRow(primaryCurrency: Currency, currencies: List[Currency]): HtmlElement = {
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
        val date        = parseOneTimeDate(dateStr)
        if name.nonEmpty && amountCents > 0 then {
          dataService.addOneTimeExpense(name, amountCents, currencyValue.now(), date).map(_ => addingOneTime.set(false))
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
          button(tpe := "button", cls := "btn btn-secondary btn-sm", "Cancel", onClick --> { _ => addingOneTime.set(false) }),
        ),
      ),
    )
  }

  private def parseOneTimeDate(dateStr: String): Option[Instant] = {
    if dateStr.isEmpty then None
    else {
      scala.util.Try {
        java.time.LocalDate.parse(dateStr).atStartOfDay(java.time.ZoneId.of("UTC")).toInstant
      }.toOption
    }
  }

  private def savingsTargetRow(
      account: SavingsAccount,
      periodContribution: Long,
      periodTxns: List[SavingsTransaction],
      savingToId: Option[SavingsAccountId],
      isExpanded: Boolean,
  ): HtmlElement = {
    val currency = account.currency
    val savedEl  = MoneyFormatter.format(periodContribution, currency)

    val (targetEl, remainingEl, progressClass) = account.plannedMonthly match {
      case Some(target) =>
        val remaining = math.max(0L, target - periodContribution)
        val cls       = if periodContribution >= target then "text-success" else "text-warning"
        (MoneyFormatter.format(target, currency), MoneyFormatter.format(remaining, currency), cls)
      case None         =>
        (span("-"), span("-"), "text-muted")
    }

    tr(
      styleAttr := "cursor: pointer",
      onClick --> { _ =>
        if isExpanded then {
          expandedSavingsIds.update(_ - account.id)
          savingToAccountId.update(id => if id.contains(account.id) then None else id)
        } else expandedSavingsIds.update(_ + account.id)
      },
      td(
        span(cls := "me-1", if isExpanded then "▼" else "▶"),
        account.name,
        span(cls := "ms-2 badge text-bg-success", currency.code),
      ),
      td(cls := "text-end font-monospace", targetEl),
      td(cls := s"text-end font-monospace $progressClass", savedEl),
      td(cls := s"text-end font-monospace $progressClass", remainingEl),
      td(),
    )
  }

  private def savingsTransactionRow(txn: SavingsTransaction, currency: Currency): HtmlElement = {
    import ssbudget.frontend.util.Formatting
    val sign     = if txn.amount >= 0 then "+" else ""
    val colorCls = if txn.amount >= 0 then "text-success" else "text-danger"
    val dateStr  = Formatting.formatDate(txn.createdAt)

    tr(
      cls := "table-light",
      td(cls     := "ps-4 text-muted small", dateStr),
      td(colSpan := 2, cls := "small", txn.note.getOrElse[String]("-")),
      td(cls     := s"text-end font-monospace small $colorCls", span(sign), MoneyFormatter.format(math.abs(txn.amount), currency)),
      td(
        Loading.actionButton(
          "×",
          () => dataService.deleteSavingsTransaction(txn.id),
          "btn btn-outline-danger btn-sm py-0",
        ),
      ),
    )
  }

  private def addTransactionButton(account: SavingsAccount): HtmlElement = {
    tr(
      cls := "table-light",
      td(colSpan := 4, cls := "ps-4"),
      td(
        button(
          cls := "btn btn-outline-primary btn-sm py-0",
          "+ Add",
          onClick --> { _ => savingToAccountId.set(Some(account.id)) },
        ),
      ),
    )
  }

  private def addSavingsTransactionRow(account: SavingsAccount, suggestedAmount: Long): HtmlElement = {
    var amountRef: org.scalajs.dom.html.Input = null
    var noteRef: org.scalajs.dom.html.Input   = null

    val addAction = Loading.actionGroup(
      "Add",
      () => {
        val amountTxt = Option(amountRef).map(_.value.trim).getOrElse("")
        val note      = Option(noteRef).map(_.value.trim).filter(_.nonEmpty)
        amountTxt.toDoubleOption match {
          case Some(amount) =>
            val amountCents = (amount * 100).toLong
            if amountCents != 0 then {
              dataService.addSavingsTransaction(account.id, amountCents, note).map(_ => savingToAccountId.set(None))
            } else Future.successful(())
          case None         => Future.successful(())
        }
      },
      "btn btn-success btn-sm py-0",
    )

    tr(
      cls := "table-info",
      td(cls    := "ps-4 text-muted small", "New"),
      td(
        colSpan := 2,
        input(
          cls         := "form-control form-control-sm",
          tpe         := "text",
          placeholder := "Note (optional)",
          onMountCallback(ctx => noteRef = ctx.thisNode.ref),
          addAction.onEnter,
        ),
      ),
      td(
        input(
          cls          := "form-control form-control-sm text-end",
          tpe          := "number",
          stepAttr     := "0.01",
          placeholder  := "Amount",
          defaultValue := (math.max(0L, suggestedAmount) / 100.0).toString,
          onMountCallback(ctx => amountRef = ctx.thisNode.ref),
          onMountFocus,
          addAction.onEnter,
        ),
      ),
      td(
        div(
          cls := "btn-group btn-group-sm",
          addAction.btn,
          button(tpe := "button", cls := "btn btn-secondary btn-sm py-0", "×", onClick --> { _ => savingToAccountId.set(None) }),
        ),
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
        td(cls := "text-end font-monospace", item.fixedEstimate.fold[HtmlElement](span("-"))(MoneyFormatter.formatPrimary)),
        td(cls := "text-end font-monospace", paidAmount.fold[HtmlElement](span("-"))(MoneyFormatter.formatPrimary)),
        td(cls := "text-center", span(cls := s"badge $statusBadge", statusLabel)),
        td(
          div(
            cls := "btn-group btn-group-sm",
            if isPaid then List(
              button(cls := "btn btn-outline-secondary btn-sm", "Edit", onClick --> { _ => editingItemId.set(Some(item.id)) }),
              Loading.actionButton(undoLabel, () => dataService.unmarkBudgetItemAsPaid(item.id), "btn btn-outline-warning btn-sm"),
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
      td(cls := "text-end font-monospace", item.fixedEstimate.fold[HtmlElement](span("-"))(MoneyFormatter.formatPrimary)),
      td(moneyInput(item.fixedEstimate, ref => inputRef = ref, autoFocus = true)),
      td(),
      td(
        saveCancel(
          onSave = () => {
            dataService.markBudgetItemAsPaid(item.id, parseCents(inputRef)).map(_ => payingItemId.set(None))
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
        td(cls := "text-end font-monospace", MoneyFormatter.formatPrimary(monthlyEstimate)),
        td(cls := "text-end font-monospace", MoneyFormatter.formatPrimary(scaledEstimate)),
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
            dataService.updateBudgetItemEstimate(item.id, parseCents(estimateRef), item.currency).map(_ => editingItemId.set(None))
          },
          onCancel = () => editingItemId.set(None),
          onDelete = () => {
            dataService.deleteBudgetItem(item.id).map(_ => editingItemId.set(None))
          },
        ),
      ),
    )
  }

  private def addItemRow(itemType: BudgetItemType, addingVar: Var[Boolean], columns: Int, currency: Currency): HtmlElement = {
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
              dataService.addBudgetItem(name, itemType, parseCents(estimateRef), currency).map(_ => addingVar.set(false))
            } else {
              Future.successful(())
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

  private def saveCancel(onSave: () => Future[Unit], onCancel: () => Unit, saveLabel: String = "Save"): HtmlElement = {
    div(
      cls := "btn-group btn-group-sm",
      Loading.actionButton(saveLabel, onSave, "btn btn-success btn-sm"),
      button(tpe := "button", cls := "btn btn-secondary btn-sm", "Cancel", onClick --> { _ => onCancel() }),
    )
  }

  private def saveCancelDelete(onSave: () => Future[Unit], onCancel: () => Unit, onDelete: () => Future[Unit]): HtmlElement = {
    div(
      cls := "btn-group btn-group-sm",
      Loading.actionButton("Save", onSave, "btn btn-primary btn-sm"),
      button(tpe := "button", cls := "btn btn-secondary btn-sm", "Cancel", onClick --> { _ => onCancel() }),
      Loading.actionButton("Del", onDelete, "btn btn-danger btn-sm"),
    )
  }
}
