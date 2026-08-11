package ssbudget.frontend.pages

import com.raquo.laminar.api.L.*
import ssbudget.frontend.components.{Loading, LoadingState}
import ssbudget.frontend.services.DataService
import ssbudget.frontend.util.{Formatting, MoneyFormatter}
import ssbudget.frontend.{Page, Router}
import ssbudget.shared.api.{CategorySummary, MonthFilter, TransactionListResponse}
import ssbudget.shared.model.*

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

object BudgetPage {

  private val dataService = DataService.instance

  private val editingItemId     = Var[Option[ExpenseDefId]](None)
  private val payingItemId      = Var[Option[ExpenseDefId]](None)
  private val addingPlanned     = Var(false)
  private val addingIncome      = Var(false)
  private val showOnlyPending   = Var(true)
  private val hidePaidBudgets   = Var(true)                     // Category Budgets: hide budgets already covered this period
  private val editingOverrideId = Var[Option[CategoryId]](None) // Category Budget whose remaining amount is being overridden

  // Category Budget drill-down: the transactions behind each expanded row. Presence of a key IS the expanded state — there's no second Var to keep in
  // step with it.
  private val budgetTxs = Var[Map[CategoryId, LoadingState[TransactionListResponse]]](Map.empty)

  /** Columns in the planned-items table: Name, Expected, Paid, Remaining, Status, Actions. */
  private val plannedItemColumns = 6

  def apply(): HtmlElement = {
    div(
      cls := "container-fluid mt-3",
      // Refresh on each visit so changes made elsewhere (e.g. flagging a category as a budget) are reflected without a manual page reload. Budget
      // drill-downs collapse too — their cached transactions would otherwise be stale after recategorizing something on the Transactions page.
      onMountCallback { _ =>
        dataService.initialize()
        budgetTxs.set(Map.empty)
      },
      h4("Budget"),
      div(
        cls := "row g-3",
        div(cls := "col-lg-7", plannedItemsCard()),
        div(cls := "col-lg-5", categoryBudgetsCard()),
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
            tr(
              th("Name"),
              th(cls := "text-end", "Expected"),
              th(cls := "text-end", "Paid"),
              th(cls := "text-end", "Remaining"),
              th(cls := "text-center", "Status"),
              th("Actions"),
            ),
          ),
          tbody(
            children <-- plannedItemRows(dataService.plannedExpenses, isIncome = false),
            child <-- addingPlanned.signal.combineWith(dataService.primaryCurrency).map {
              case (true, currency) => addItemRow(BudgetItemType.PlannedExpense, addingPlanned, currency)
              case (false, _)       => emptyNode
            },
            tr(cls := "table-secondary", td(colSpan := plannedItemColumns, cls := "py-1 small text-muted", "— Incomes —")),
            children <-- plannedItemRows(dataService.plannedIncomes, isIncome = true),
            child <-- addingIncome.signal.combineWith(dataService.primaryCurrency).map {
              case (true, currency) => addItemRow(BudgetItemType.PlannedIncome, addingIncome, currency)
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

  /** Categories with a budget type set: this-period spend vs the category's mean monthly spend, rendered per type (Steady pace bar / Bill paid-state
    * / Subscription fixed pool).
    */
  private def categoryBudgetsCard(): HtmlElement = {
    // A budget is "paid" (covered) when nothing more is expected before the next paycheck — i.e. remaining <= 0 (an override of 0 does exactly
    // that). Steady budgets keep a remaining-time share until the period ends, so they stay visible; Bill/Subscription drop off once covered.
    div(
      cls := "card",
      div(
        cls := "card-header py-2 d-flex justify-content-between align-items-center",
        span("Category Budgets"),
        div(
          cls := "form-check form-switch mb-0",
          input(
            cls     := "form-check-input",
            tpe     := "checkbox",
            idAttr  := "hidePaidBudgets",
            checked <-- hidePaidBudgets.signal,
            onChange.mapToChecked --> hidePaidBudgets.writer,
          ),
          label(cls := "form-check-label small", forId := "hidePaidBudgets", "Hide paid"),
        ),
      ),
      div(
        cls := "card-body",
        children <-- dataService.budgetedCategories
          .combineWith(dataService.periodElapsedFraction)
          .combineWith(hidePaidBudgets.signal)
          .map { case (cats, elapsed, hidePaid) =>
            val visible = if hidePaid then cats.filter(_.remainingCents(elapsed) > 0) else cats
            if cats.isEmpty then List(
              div(
                cls := "text-muted small",
                "No category budgets yet. Set a budget type (Steady / Bill / Subscription) on a category on the Transactions page.",
              ),
            )
            else if visible.isEmpty then List(
              div(cls := "text-muted small", "All budgets are covered this period ✓ (turn off “Hide paid” to see them)."),
            )
            else visible.map(c => categoryBudgetRow(c, elapsed))
          },
      ),
    )
  }

  private def categoryBudgetRow(s: CategorySummary, elapsed: Double): HtmlElement = {
    val budget                 = s.avgMonthlyCents
    val spent                  = s.currentPeriodSpentCents
    val bt                     = s.category.budgetType.getOrElse(CategoryBudgetType.Steady)
    val remaining              = s.remainingCents(elapsed)
    val isOverridden           = s.overrideRemainingCents.isDefined
    def money(c: Long): String = MoneyFormatter.formatSimple(c, s.currency)

    // The colored bar + footer differ per budget type; the header (name + spent/budget) is shared.
    val (bar, footerLeft, footerRight): (HtmlElement, String, String) = bt match {
      case CategoryBudgetType.Bill         =>
        // An override decides the paid state (that's the point of setting one); otherwise any spend this period counts as the payment.
        val paid = if isOverridden then remaining <= 0 else spent > 0
        val b    = div(
          cls       := "progress",
          styleAttr := "height: 1.1rem",
          div(cls := s"progress-bar ${if paid then "bg-success" else "bg-secondary"}", styleAttr := s"width: ${if paid then 100 else 0}%"),
        )
        (b, if paid then "paid ✓" else "not paid yet", if paid then "" else s"reserve ${money(remaining)}")
      case CategoryBudgetType.Subscription =>
        // Fill is what's been covered (budget − remaining), so an override moves the bar too.
        val fillPct =
          if budget <= 0 then (if remaining <= 0 then 100.0 else 0.0)
          else math.min(100.0, math.max(0.0, (budget - remaining).toDouble / budget * 100.0))
        val over    = budget > 0 && spent > budget
        val b       = div(
          cls       := "progress",
          styleAttr := "height: 1.1rem",
          div(cls := s"progress-bar ${if over then "bg-danger" else "bg-success"}", styleAttr := s"width: $fillPct%"),
        )
        (b, if remaining <= 0 then "fully paid ✓" else "fixed pool", if remaining > 0 then s"${money(remaining)} left" else "")
      case CategoryBudgetType.Steady       =>
        val fillPct    = if budget <= 0 then (if spent > 0 then 100.0 else 0.0) else math.min(100.0, spent.toDouble / budget * 100.0)
        val pacePct    = math.max(0.0, math.min(100.0, elapsed * 100.0))
        val expected   = (budget * elapsed).toLong
        val overBudget = budget > 0 && spent > budget
        val overPace   = budget > 0 && spent > expected
        val barColor   = if overBudget then "bg-danger" else if overPace then "bg-warning" else "bg-success"
        val paceLabel  =
          if budget <= 0 then "no budget history yet"
          else if overBudget then "over budget"
          else if overPace then "over pace"
          else "under pace ✓"
        val b          = div(
          cls       := "progress position-relative",
          styleAttr := "height: 1.1rem",
          div(cls     := s"progress-bar $barColor", styleAttr := s"width: $fillPct%"),
          // pace marker: where spending would be if it tracked the period elapsed exactly
          div(
            styleAttr := s"position: absolute; top: 0; bottom: 0; left: $pacePct%; width: 2px; background: rgba(0,0,0,0.65)",
            title     := "expected by now",
          ),
        )
        (b, paceLabel, s"reserve ${money(remaining)}")
    }

    div(
      cls := "mb-3",
      // The header line is the drill-down toggle: "why is this budget already at X?" is answered by the transactions right under the bar.
      div(
        cls       := "d-flex justify-content-between user-select-none",
        styleAttr := "cursor: pointer",
        title     := "Show the transactions behind this spend",
        onClick --> { _ => toggleBudgetExpansion(s.category.id) },
        span(
          cls    := "fw-semibold",
          span(
            cls    := "text-muted me-1",
            child.text <-- rowExpanded(s.category.id).map(if _ then "▾" else "▸"),
          ),
          s.category.name,
          span(cls := "badge text-bg-light text-muted ms-2", CategoryBudgetType.asString(bt)),
          if isOverridden then span(cls := "badge text-bg-warning ms-1", title := "remaining amount set manually", "manual") else emptyNode,
        ),
        span(cls := "font-monospace small", s"${money(spent)} / ${money(budget)}"),
      ),
      bar,
      // Both bindings below narrow to THIS row and dedupe, so one row expanding or opening its editor doesn't re-render every other row.
      child <-- editingOverrideId.signal.map(_.contains(s.category.id)).distinct.map { editing =>
        if editing then overrideEditRow(s, remaining, isOverridden)
        else
          div(
            cls := "d-flex justify-content-between small text-muted",
            span(footerLeft),
            span(
              cls       := "text-decoration-underline",
              styleAttr := "cursor: pointer",
              title     := "Set the remaining amount manually",
              onClick --> { _ => editingOverrideId.set(Some(s.category.id)) },
              if footerRight.nonEmpty then footerRight else "set remaining",
            ),
          )
      },
      child <-- budgetTxs.signal.map(_.get(s.category.id)).distinct.map {
        case Some(state) => categoryBudgetTransactions(s, state)
        case None        => emptyNode
      },
    )
  }

  private def rowExpanded(categoryId: CategoryId): Signal[Boolean] = budgetTxs.signal.map(_.contains(categoryId)).distinct

  /** Expand/collapse a category budget's transaction list, fetching on expand. Collapsing drops the cached rows, so re-expanding always shows current
    * data (categories get recategorized on the Transactions page while this page stays open).
    */
  private def toggleBudgetExpansion(categoryId: CategoryId): Unit = {
    if budgetTxs.now().contains(categoryId) then budgetTxs.update(_ - categoryId)
    else {
      budgetTxs.update(_.updated(categoryId, LoadingState.Loading))
      // Only ever fill in a row that's still expanded: collapsing mid-flight drops the key, and the late response mustn't put it back.
      def complete(state: LoadingState[TransactionListResponse]): Unit =
        budgetTxs.update(m => if m.contains(categoryId) then m.updated(categoryId, state) else m)
      dataService.categoryPeriodTransactions(categoryId, budgetDrillDownRows + 1).onComplete {
        case Success(page) => complete(LoadingState.Loaded(page))
        case Failure(ex)   => complete(LoadingState.Error(ex.getMessage))
      }
    }
  }

  /** Transactions shown inline under a category budget before falling back to the "+N more" link — enough to spot the outlier that moved the number,
    * without pushing the other budgets off screen.
    */
  private val budgetDrillDownRows = 8

  /** The current period's transactions behind a category budget's spend: compact and read-only. Fixing a miscategorized transaction happens on the
    * Transactions page, which the footer link opens with this category and window already filtered.
    */
  private def categoryBudgetTransactions(s: CategorySummary, state: LoadingState[TransactionListResponse]): HtmlElement = {
    val target  = Page.Transactions(category = Some(s.category.id.value), month = Some(MonthFilter.CurrentPeriod))
    def linkOut = a(cls := "small text-nowrap", Router.linkTo(target), "open in Transactions →")

    div(
      cls := "border-start ps-2 ms-1 mt-1",
      state match {
        case LoadingState.Loading                            => div(cls := "small text-muted", "Loading…")
        case LoadingState.Error(msg)                         => div(cls := "small text-danger", s"Couldn't load transactions: $msg")
        case LoadingState.Loaded(page) if page.items.isEmpty =>
          div(cls := "d-flex justify-content-between", span(cls := "small text-muted", "No transactions this period."), linkOut)
        case LoadingState.Loaded(page)                       =>
          val shown = page.items.take(budgetDrillDownRows)
          div(
            shown.map(budgetTransactionRow),
            div(
              cls := "d-flex justify-content-between mt-1",
              // `total` counts the whole match, not just the rows fetched, so this stays exact however few we asked for.
              span(cls := "small text-muted", if page.total > shown.size then s"+${page.total - shown.size} more" else ""),
              linkOut,
            ),
          )
      },
    )
  }

  private def budgetTransactionRow(t: BankTransaction): HtmlElement = {
    // Everything here is spend, so outflows stay neutral; an inflow (refund) is called out because it SUBTRACTS from the category's spend.
    val amountCls = if t.amountCents < 0 then "" else "text-success"
    div(
      cls := "d-flex gap-2 small",
      span(cls := "text-muted text-nowrap font-monospace", Formatting.formatDateShort(t.bookedAt)),
      span(cls := "text-truncate flex-grow-1", title := t.description, t.description),
      span(cls := s"font-monospace text-nowrap $amountCls", MoneyFormatter.formatSimple(t.amountCents, t.currency)),
    )
  }

  /** Inline editor for a category budget's remaining amount: type an amount, mark it fully paid (0), or clear the override to go back to the computed
    * value. Applies to the current period only.
    */
  private def overrideEditRow(s: CategorySummary, remaining: Long, isOverridden: Boolean): HtmlElement = {
    var amountRef: org.scalajs.dom.html.Input = null

    def setOverride(cents: => Long): () => Future[Unit] =
      () => dataService.setCategoryBudgetOverride(s.category.id, cents).map(_ => editingOverrideId.set(None))

    val saveAction = Loading.actionGroup("Save", setOverride(parseCents(amountRef)), "btn btn-success btn-sm py-0")

    div(
      cls := "d-flex align-items-center gap-2 mt-1",
      span(cls    := "small text-muted text-nowrap", "Remaining"),
      div(
        styleAttr := "width: 7rem",
        moneyInput(Some(remaining), ref => amountRef = ref, autoFocus = true).amend(saveAction.onEnter),
      ),
      div(
        cls       := "btn-group btn-group-sm",
        saveAction.btn,
        Loading.actionButton("Paid", setOverride(0L), "btn btn-outline-success btn-sm py-0"),
        if isOverridden then Loading.actionButton(
          "Clear",
          () => dataService.clearCategoryBudgetOverride(s.category.id).map(_ => editingOverrideId.set(None)),
          "btn btn-outline-secondary btn-sm py-0",
        )
        else emptyNode,
        button(tpe := "button", cls := "btn btn-secondary btn-sm py-0", "×", onClick --> { _ => editingOverrideId.set(None) }),
      ),
    )
  }

  /** Rows for one planned-item group, honouring the "Pending only" switch. Pending means NOT SETTLED, so a part-paid item stays on screen with its
    * remainder — the whole point of allowing instalments.
    */
  private def plannedItemRows(items: Signal[List[BudgetItemDefinition]], isIncome: Boolean): Signal[List[HtmlElement]] =
    items
      .combineWith(dataService.currentPeriodRecords)
      .combineWith(payingItemId.signal)
      .combineWith(editingItemId.signal)
      .combineWith(showOnlyPending.signal)
      .map { case (items, records, payingId, editingId, pendingOnly) =>
        val visible =
          if pendingOnly then items.filter(item => !records.exists(r => r.expenseDefId == item.id && r.settled))
          else items
        visible.map(item => plannedItemRow(item, records.find(_.expenseDefId == item.id), payingId, editingId, isIncome))
      }

  private def plannedItemRow(
      item: BudgetItemDefinition,
      record: Option[ExpenseRecord],
      payingId: Option[ExpenseDefId],
      editingId: Option[ExpenseDefId],
      isIncome: Boolean,
  ): HtmlElement = {
    val estimate  = item.estimateCents
    val paid      = record.map(_.paidCents).filter(_ > 0)
    val remaining = ExpenseRecord.remainingFor(record, estimate)
    val settled   = record.exists(_.settled)
    val partial   = record.exists(_.isPartiallyPaid)

    if payingId.contains(item.id) then payItemRow(item, remaining, isIncome)
    else if editingId.contains(item.id) then editItemRow(item)
    else {
      val (statusLabel, statusBadge) =
        if settled then (if isIncome then "Received" else "Paid", "text-bg-success")
        else if partial then ("Partial", "text-bg-warning")
        else ("Pending", "text-bg-secondary")

      val actionLabel = if isIncome then "Receive" else "Pay"
      // Settled rows undo everything; part-paid rows clear the instalments so far. Same action, different word for it.
      val resetLabel  = if settled then (if isIncome then "Undo" else "Unpay") else "Reset"

      tr(
        td(item.name),
        td(cls := "text-end font-monospace", MoneyFormatter.formatPrimary(item.estimateCents)),
        td(cls := "text-end font-monospace", paid.fold[HtmlElement](span(cls := "text-muted", "-"))(MoneyFormatter.formatPrimary)),
        // Remaining is what actually feeds the free-money calc, so a settled row shows a plain 0 rather than the untouched estimate.
        td(
          cls  := s"text-end font-monospace ${if remaining > 0 then "" else "text-muted"}",
          MoneyFormatter.formatPrimary(remaining),
        ),
        td(cls := "text-center", span(cls := s"badge $statusBadge", statusLabel)),
        td(
          div(
            cls := "btn-group btn-group-sm",
            Option.when(!settled)(
              button(cls := "btn btn-outline-success btn-sm", actionLabel, onClick --> { _ => payingItemId.set(Some(item.id)) }),
            ),
            button(cls := "btn btn-outline-secondary btn-sm", "Edit", onClick --> { _ => editingItemId.set(Some(item.id)) }),
            // Nothing to undo on an untouched row, so this only appears once something has been paid.
            Option.when(settled || partial)(
              Loading.actionButton(resetLabel, () => dataService.resetBudgetItemPayment(item.id), "btn btn-outline-warning btn-sm"),
            ),
          ),
        ),
      )
    }
  }

  /** Payment editor for one planned item. The amount ADDS to what's already paid, so it defaults to the remainder: "Pay" settles the item at that
    * amount (closing it even if it came in under estimate), "Part" banks the instalment and leaves the rest outstanding.
    */
  private def payItemRow(item: BudgetItemDefinition, remaining: Long, isIncome: Boolean): HtmlElement = {
    var inputRef: org.scalajs.dom.html.Input = null

    def pay(settle: Boolean): () => Future[Unit] =
      () => dataService.payBudgetItem(item.id, parseCents(inputRef), settle).map(_ => payingItemId.set(None))

    val settleAction = Loading.actionGroup(if isIncome then "Receive" else "Pay", pay(settle = true), "btn btn-success btn-sm")

    tr(
      cls := "table-info",
      td(item.name),
      td(cls := "text-end font-monospace", MoneyFormatter.formatPrimary(item.estimateCents)),
      td(moneyInput(Some(remaining), ref => inputRef = ref, autoFocus = true).amend(settleAction.onEnter)),
      td(),
      td(),
      td(
        div(
          cls := "btn-group btn-group-sm",
          settleAction.btn,
          Loading.actionButton("Part", pay(settle = false), "btn btn-outline-success btn-sm"),
          button(tpe := "button", cls := "btn btn-secondary btn-sm", "Cancel", onClick --> { _ => payingItemId.set(None) }),
        ),
      ),
    )
  }

  private def editItemRow(item: BudgetItemDefinition): HtmlElement = {
    var nameRef: org.scalajs.dom.html.Input     = null
    var estimateRef: org.scalajs.dom.html.Input = null

    tr(
      cls := "table-warning",
      td(textInput(item.name, ref => nameRef = ref)),
      td(moneyInput(Some(item.estimateCents), ref => estimateRef = ref, autoFocus = true)),
      td(colSpan := plannedItemColumns - 3), // gap: name + amount + actions are the only real cells here
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

  private def addItemRow(itemType: BudgetItemType, addingVar: Var[Boolean], currency: Currency): HtmlElement = {
    var nameRef: org.scalajs.dom.html.Input     = null
    var estimateRef: org.scalajs.dom.html.Input = null
    val isIncome                                = itemType == BudgetItemType.PlannedIncome
    val namePlaceholder                         = if isIncome then "Income name" else "Expense name"
    val amountPlaceholder                       = if isIncome then "Expected" else "Estimate"

    tr(
      cls := "table-primary",
      td(textInput("", ref => nameRef = ref, placeholderText = namePlaceholder, autoFocus = true)),
      td(moneyInput(None, ref => estimateRef = ref, placeholderText = amountPlaceholder)),
      td(colSpan := plannedItemColumns - 3), // gap: name + amount + actions are the only real cells here
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
