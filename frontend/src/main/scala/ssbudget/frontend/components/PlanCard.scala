package ssbudget.frontend.components

import com.raquo.laminar.api.L.*
import ssbudget.frontend.components.InlineEdit.*
import ssbudget.frontend.services.DataService
import ssbudget.frontend.util.{Formatting, MoneyFormatter}
import ssbudget.frontend.{Page, Router}
import ssbudget.shared.api.{CategorySummary, MonthFilter, TransactionListResponse}
import ssbudget.shared.model.*

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

/** Everything expected to move before the next paycheck, as one list.
  *
  * Two kinds of entry share it, and the CATEGORY BUDGET is the primary one: recurring spending is planned by flagging a category and letting real
  * transactions drive the expectation. A PLANNED ITEM is the escape hatch beside it — declared by hand for what no category can predict (a salary, a
  * savings top-up, an exceptional bill) and closed by settling it.
  *
  * Because budgets are the common case, every entry is drawn the budget way — name, a bar of how far through it the period is, and one line of state
  * — rather than as a grid of columns. A manual entry's bar is simply what's been paid of its estimate. Entries are grouped by DIRECTION (expenses,
  * incomes) and every amount is a magnitude in that direction, so an income entry's "remaining" is what's still expected to arrive.
  */
object PlanCard {

  private val dataService = DataService.instance

  private val editingItemId     = Var[Option[ExpenseDefId]](None)
  private val payingItemId      = Var[Option[ExpenseDefId]](None)
  private val addingExpense     = Var(false)
  private val addingIncome      = Var(false)
  private val hideDone          = Var(true)
  private val editingOverrideId = Var[Option[CategoryId]](None)

  /** Drill-down: the transactions behind each expanded category entry. Presence of a key IS the expanded state — there's no second Var to keep in
    * step with it.
    */
  private val budgetTxs = Var[Map[CategoryId, LoadingState[TransactionListResponse]]](Map.empty)

  /** Transactions shown inline under a category entry before falling back to the "+N more" link — enough to spot the outlier that moved the number,
    * without pushing the rest of the plan off screen.
    */
  private val drillDownRows = 8

  private enum Entry {
    case Item(definition: BudgetItemDefinition, record: Option[ExpenseRecord])
    case Budget(summary: CategorySummary, elapsed: Double)
  }

  /** An entry reduced to what the list needs, with every amount as a magnitude in the entry's own direction: `actual` is spent for an expense and
    * received for an income, `remaining` is what's still to spend or still to arrive. One definition of the sort key and of "done", so the sections
    * and the hide switch can't disagree with the entries they show.
    */
  private case class Line(entry: Entry) {

    val isIncome: Boolean = entry match {
      case Entry.Item(d, _)   => d.itemType == BudgetItemType.PlannedIncome
      case Entry.Budget(s, _) => s.isIncome
    }

    val name: String = entry match {
      case Entry.Item(d, _)   => d.name
      case Entry.Budget(s, _) => s.category.name
    }

    val expected: Long = entry match {
      case Entry.Item(d, _)   => d.estimateCents
      case Entry.Budget(s, _) => s.expectedMagnitude
    }

    val actual: Long = entry match {
      case Entry.Item(_, r)   => r.map(_.paidCents).getOrElse(0L)
      case Entry.Budget(s, _) => s.movedMagnitude
    }

    val remaining: Long = entry match {
      case Entry.Item(d, r)   => ExpenseRecord.remainingFor(r, d.estimateCents)
      case Entry.Budget(s, e) => s.remainingMagnitude(e)
    }

    /** Nothing further expected this period. A planned item is done when SETTLED (however little was paid — a zero-estimate item stays visible and
      * editable), a budget when its remaining reaches zero in either direction.
      */
    val isDone: Boolean = entry match {
      case Entry.Item(_, r)   => r.exists(_.settled)
      case Entry.Budget(_, _) => remaining == 0
    }

    val isBudget: Boolean = entry match {
      case Entry.Budget(_, _) => true
      case Entry.Item(_, _)   => false
    }
  }

  def apply(): HtmlElement =
    div(
      cls := "card mb-3",
      // Transient state is cleared on every visit: a half-finished edit shouldn't resume, and a cached drill-down would be stale after recategorizing
      // something on the Transactions page.
      onMountCallback { _ =>
        editingItemId.set(None)
        payingItemId.set(None)
        addingExpense.set(false)
        addingIncome.set(false)
        editingOverrideId.set(None)
        budgetTxs.set(Map.empty)
      },
      div(
        cls := "card-header py-2 d-flex justify-content-between align-items-center",
        span("Plan"),
        div(
          cls := "d-flex align-items-center gap-2",
          div(
            cls := "form-check form-switch mb-0",
            input(
              cls     := "form-check-input",
              tpe     := "checkbox",
              idAttr  := "hideDone",
              checked <-- hideDone.signal,
              onChange.mapToChecked --> hideDone.writer,
            ),
            label(cls := "form-check-label small", forId := "hideDone", "Hide done"),
          ),
          div(
            cls := "btn-group btn-group-sm",
            button(cls := "btn btn-outline-primary", "+ Expense", onClick --> { _ => addingExpense.set(true) }),
            button(cls := "btn btn-outline-success", "+ Income", onClick --> { _ => addingIncome.set(true) }),
          ),
        ),
      ),
      div(
        cls := "card-body py-2",
        child <-- section(income = false),
        child <-- section(income = true),
        // Both groups render nothing when they have no entries, so a plan with nothing in it needs to say so itself.
        child.maybe <-- planLines.map(lines => Option.when(lines.isEmpty)(div(cls := "text-muted small", "Nothing planned yet."))),
      ),
      footer(),
    )

  /** Every entry of the plan, both directions, built once — the two groups and the empty state all read from here rather than each combining the same
    * five sources for themselves.
    */
  private val planLines: Signal[List[Line]] =
    dataService.plannedExpenses
      .combineWith(dataService.plannedIncomes)
      .combineWith(dataService.currentPeriodRecords)
      .combineWith(dataService.budgetedCategories)
      .combineWith(dataService.periodElapsedFraction)
      .map { case (expenses, incomes, records, budgets, elapsed) =>
        val recordFor = records.groupBy(_.expenseDefId)
        val items     = (expenses ++ incomes).map(d => Line(Entry.Item(d, recordFor.get(d.id).flatMap(_.headOption))))
        budgets.map(s => Line(Entry.Budget(s, elapsed))) ++ items
      }

  /** One direction's entries: budgets first (they carry the recurring spending), then the hand-declared items, each group biggest-outstanding first.
    * A stable grouping beats one merged ordering here — paying something shouldn't reshuffle the list under the cursor.
    */
  private def section(income: Boolean): Signal[Node] =
    planLines
      .combineWith(hideDone.signal)
      .combineWith(if income then addingIncome.signal else addingExpense.signal)
      .combineWith(dataService.primaryCurrency)
      .map { case (lines, hide, adding, currency) =>
        val visible = lines.filter(_.isIncome == income).filterNot(l => hide && l.isDone)
        val ordered =
          visible.filter(_.isBudget).sortBy(l => (-l.remaining, l.name)) ++ visible.filterNot(_.isBudget).sortBy(l => (-l.remaining, l.name))

        if ordered.isEmpty && !adding then emptyNode
        else
          div(
            cls := (if income then "mt-3 pt-2 border-top" else ""),
            div(cls := "text-muted small text-uppercase mb-2", if income then "Incomes" else "Expenses"),
            ordered.map(lineBlock),
            if adding then addForm(income, currency) else emptyNode,
          )
      }

  // ---- One entry ------------------------------------------------------------------------------

  private def lineBlock(line: Line): HtmlElement = {
    val state = lineState(line)

    div(
      cls := "mb-3",
      div(
        cls := "d-flex justify-content-between",
        header(line),
        // Magnitudes in the entry's direction: received / expected for an income, spent / budgeted for an expense.
        span(cls := "font-monospace small text-nowrap ms-2", s"${MoneyFormatter.formatBare(line.actual)} / ${money(line.expected)}"),
      ),
      // An entry with a marker has nothing to fill up, so it gets no bar — the marker on its state line says all there is to say.
      if state.marker.isDefined then emptyMod else progressBar(state),
      // The state line doubles as the entry's controls; an open editor takes its place so the bar above stays visible for context.
      child <-- openEditor(line).map {
        case Some(editor) => editor
        case None         =>
          div(
            cls := "d-flex justify-content-between align-items-center small text-muted",
            span(
              state.marker.map(done => span(cls := (if done then "text-success me-1" else "text-muted me-1"), if done then "✔" else "○")),
              state.label,
            ),
            actions(line),
          )
      },
      drillDownSlot(line),
    )
  }

  /** The expanded transactions under a category entry; a manual entry has nothing to expand. */
  private def drillDownSlot(line: Line): Modifier[HtmlElement] =
    line.entry match {
      case Entry.Budget(s, _) =>
        child <-- budgetTxs.signal.map(_.get(s.category.id)).distinct.map {
          case Some(txs) => drillDown(s, txs)
          case None      => emptyNode
        }
      case Entry.Item(_, _)   => emptyMod
    }

  /** Name, plus the badges that say what kind of entry this is: its budget type when transaction-derived, `manual` when hand-declared, and the
    * exceptions on top of that (an overridden budget, an income budget).
    */
  private def header(line: Line): HtmlElement =
    line.entry match {
      case Entry.Budget(s, _) =>
        val bt       = s.category.budgetType.getOrElse(CategoryBudgetType.Steady)
        span(
          cls       := "fw-semibold user-select-none",
          styleAttr := "cursor: pointer",
          title     := "Show the transactions behind this spend",
          onClick --> { _ => toggleBudgetExpansion(s.category.id) },
          span(cls := "text-muted me-1", child.text <-- rowExpanded(s.category.id).map(if _ then "▾" else "▸")),
          s.category.name,
          span(cls := "badge text-bg-light text-muted ms-2", CategoryBudgetType.asString(bt)),
          if s.overrideRemainingCents.isDefined then span(
            cls   := "badge text-bg-warning ms-1",
            title := "remaining amount set by hand for this period",
            "override",
          )
          else emptyNode,
        )
      case Entry.Item(d, _)   =>
        span(
          cls := "fw-semibold",
          // Aligns with a budget's caret, which is a toggle a manual entry has nothing to expand.
          span(cls := "me-1", styleAttr                            := "visibility: hidden", "▸"),
          d.name,
          span(cls := "badge text-bg-light text-muted ms-2", title := "declared by hand, not derived from transactions", "manual"),
        )
    }

  /** How an entry stands: the words for the state line, plus the four facts its bar has to carry at once.
    *
    * The bar is scaled to `max(plan, spent + reserved)` — the period's CURRENT forecast rather than the original plan — so it can never saturate:
    * going over the plan grows the scale instead of pinning a red bar at 100% and hiding what's still reserved on top. Within that scale:
    *   - `spentPct` is what has actually moved, drawn solid in the state's colour;
    *   - `reservedPct` is what's still expected, drawn striped and neutral right after it: a plan, not a fact;
    *   - `planPct` is where the ORIGINAL plan ended. It's the width of the bar proper; anything past it is drawn as a thinner tail sticking out of
    *     that bar, so an overrun reads as "the plan, and then this much more" rather than as a line drawn across a uniform bar. 100 when nothing
    *     overran.
    *   - `pacePct` marks where spending would be if it tracked the period exactly — the point of a Steady budget.
    */
  private case class LineState(
      label: String,
      spentPct: Double,
      reservedPct: Double,
      planPct: Double,
      color: String,
      pacePct: Option[Double],
      spentTitle: String,
      reservedTitle: String,
      planTitle: String,
      // Set for an entry that either happened this period or didn't — a Bill. A bar of 0% or 100% dresses that up as a proportion it isn't, so those get
      // a checklist marker (`Some(done)`) and no bar at all, which also makes them a line shorter than the entries that really do fill up.
      marker: Option[Boolean],
  ) {
    def overran: Boolean = planPct < 100.0
  }

  private def lineState(line: Line): LineState = {
    val income                         = line.isIncome
    val plan                           = line.expected
    // A net inflow on an expense category (refunds beating spend) would otherwise invert the bar; nothing has moved in the budget's direction.
    val spent                          = math.max(0L, line.actual)
    val reserved                       = math.max(0L, line.remaining)
    val complete                       = line.remaining == 0
    // An income arriving late isn't the failure overspending is, so its bar only distinguishes "still coming" from "all in".
    def fill(alarm: => String): String = if income then (if complete then "bg-success" else "bg-primary") else alarm

    val (label, color, paceAt, marker) = line.entry match {
      case Entry.Item(_, record) =>
        val settled = record.exists(_.settled)
        val partial = record.exists(_.isPartiallyPaid)
        val text    =
          if settled then (if income then "received ✓" else "paid ✓")
          else if partial then s"${if income then "received" else "paid"} ${MoneyFormatter.formatBare(spent)} of ${money(plan)}"
          else if income then "not received yet"
          else "not paid yet"
        (text, if settled then "bg-success" else if partial then "bg-primary" else "bg-secondary", None, None)

      case Entry.Budget(s, elapsed) =>
        val isOverridden = s.overrideRemainingCents.isDefined
        s.category.budgetType.getOrElse(CategoryBudgetType.Steady) match {
          case CategoryBudgetType.Bill         =>
            // An override decides the settled state (that's the point of setting one); otherwise any movement this period counts as the payment.
            val isSettled = if isOverridden then complete else spent > 0
            // No trailing tick in the words here: the marker in front of them carries it.
            val text      =
              if isSettled then (if income then "received" else "paid")
              else if income then "not received yet"
              else "not paid yet"
            (text, if isSettled then "bg-success" else "bg-secondary", None, Some(isSettled))
          case CategoryBudgetType.Subscription =>
            val over = plan > 0 && spent > plan
            val text = if complete then (if income then "fully received ✓" else "fully paid ✓") else "fixed pool"
            (text, fill(if over then "bg-danger" else "bg-success"), None, None)
          case CategoryBudgetType.Steady       =>
            val expectedByNow = (plan * elapsed).toLong
            val overAll       = plan > 0 && spent > plan
            val overPace      = plan > 0 && spent > expectedByNow
            val text          =
              if plan <= 0 then "no budget history yet"
              else if income then (if overAll then "all in ✓" else if overPace then "ahead of pace ✓" else "behind pace")
              else if overAll then "over budget"
              else if overPace then "over pace"
              else "under pace ✓"
            (
              text,
              fill(if overAll then "bg-danger" else if overPace then "bg-warning" else "bg-success"),
              Option.when(plan > 0)(expectedByNow),
              None,
            )
        }
    }

    val forecast     = math.max(plan, spent + reserved)
    def pct(v: Long) = if forecast <= 0 then 0.0 else math.min(100.0, math.max(0.0, v.toDouble / forecast * 100.0))
    val spentPct     = pct(spent)
    val movedWord    = if income then "received" else "spent"

    LineState(
      label = label,
      spentPct = spentPct,
      // Clamped against what the spend already took, so rounding can't push the pair past the bar's width.
      reservedPct = math.max(0.0, math.min(100.0 - spentPct, pct(reserved))),
      // A plan of zero (no history yet) has no bar of its own to overrun, so the whole width is the plan's.
      planPct = if plan > 0 && forecast > plan then pct(plan) else 100.0,
      color = color,
      pacePct = paceAt.map(pct),
      spentTitle = s"$movedWord ${money(spent)}",
      reservedTitle = s"${if income then "still expected" else "reserved"} ${money(reserved)}",
      planTitle =
        if plan > 0 && forecast > plan then s"plan ${money(plan)} · forecast ${money(forecast)} · over plan by ${money(forecast - plan)}"
        else s"plan ${money(plan)}",
      marker = marker,
    )
  }

  /** A span of the bar in forecast percent, and how to paint it. */
  private case class Band(fromPct: Double, toPct: Double, cls: String, tooltip: String)

  /** Vertical inset applied to whatever lies past the plan: the fill steps down to a slimmer band, so the plan's end shows as a shoulder in one
    * continuous bar. `background-clip: padding-box` is what keeps the colour out of the transparent borders.
    */
  private val beyondPlanStyle = "border-top: 4px solid transparent; border-bottom: 4px solid transparent; background-clip: padding-box"

  /** The bar: one continuous track scaled to the forecast, whose fill runs at full height up to the plan and then steps down to a slimmer band for
    * anything beyond it. The shoulder where it steps is the plan — visible however full the bar is, which is exactly what a line drawn across it
    * wasn't.
    */
  private def progressBar(state: LineState): HtmlElement = {
    val bands = List(
      Band(0.0, state.spentPct, s"progress-bar ${state.color}", state.spentTitle),
      // Striped and neutral: this part of the bar is what's still expected, not what has happened.
      Band(
        state.spentPct,
        state.spentPct + state.reservedPct,
        "progress-bar progress-bar-striped bg-secondary opacity-25",
        state.reservedTitle,
      ),
    )

    div(
      cls       := "progress position-relative",
      styleAttr := "height: 1.1rem",
      title     := state.planTitle,
      bands.flatMap(splitAtPlan(_, state.planPct)),
      // A clean cut where the plan's bar ends, so the shoulder is crisp even when both sides of it are filled the same way.
      Option.when(state.overran)(shoulder(state.planPct, state.planTitle)),
      state.pacePct.map(paceMark),
    )
  }

  /** The plan's right edge: a light hairline with a faint dark edge, reading as a cut in the bar rather than as another marker on it. */
  private def shoulder(pct: Double, tooltip: String): HtmlElement =
    div(
      styleAttr := s"position: absolute; top: 0; bottom: 0; left: ${math.max(0.0, math.min(100.0, pct))}%; width: 1px; " +
        "background: rgba(255,255,255,0.95); box-shadow: 0 0 0 0.5px rgba(0,0,0,0.2); z-index: 2",
      title     := tooltip,
    )

  /** One band as up to two elements: the part within the plan at full height, and the part past it stepped down. */
  private def splitAtPlan(band: Band, planPct: Double): List[HtmlElement] = {
    def part(fromPct: Double, toPct: Double, beyondPlan: Boolean): Option[HtmlElement] = {
      val width = toPct - fromPct
      Option.when(width > 0.01)(
        div(
          cls       := band.cls,
          styleAttr := s"width: $width%" + (if beyondPlan then s"; $beyondPlanStyle" else ""),
          title     := band.tooltip,
        ),
      )
    }
    List(
      part(band.fromPct, math.min(band.toPct, planPct), beyondPlan = false),
      part(math.max(band.fromPct, planPct), band.toPct, beyondPlan = true),
    ).flatten
  }

  /** Where spending would be if it tracked the period exactly. A hairline pair — dark with a light edge — so it stays visible over a filled bar and
    * over the empty track alike, without slicing the bar in two the way a heavy line does.
    */
  private def paceMark(pct: Double): HtmlElement =
    div(
      styleAttr := s"position: absolute; top: 0; bottom: 0; left: ${math.max(0.0, math.min(100.0, pct))}%; width: 1px; " +
        "background: rgba(0,0,0,0.45); box-shadow: 1px 0 0 rgba(255,255,255,0.55); z-index: 1",
      title     := "expected by now",
    )

  /** What's left, and how to close it. A budget is adjusted by clicking the amount it has reserved — the editor that opens is where "paid" lives, so
    * the state line stays a statement rather than a row of buttons. A manual entry instead gets the actions that close it, since paying is what it's
    * for.
    */
  private def actions(line: Line): HtmlElement = {
    val word          = if line.isIncome then "expected" else "reserved"
    val remainingText = if line.remaining == 0 then "" else s"$word ${money(line.remaining)}"

    line.entry match {
      case Entry.Budget(s, _)    =>
        span(
          cls       := "font-monospace text-decoration-underline",
          styleAttr := "cursor: pointer",
          title     := "Set the remaining amount by hand for this period",
          onClick --> { _ => editingOverrideId.set(Some(s.category.id)) },
          if remainingText.nonEmpty then remainingText else "set remaining",
        )
      case Entry.Item(d, record) =>
        val settled = record.exists(_.settled)
        val partial = record.exists(_.isPartiallyPaid)
        // Settled entries undo everything; part-paid ones clear the instalments so far. Same action, different word for it.
        val reset   = if settled then (if line.isIncome then "Undo" else "Unpay") else "Reset"
        div(
          cls := "d-flex align-items-center",
          span(cls := "font-monospace me-2", remainingText),
          div(
            cls    := "btn-group btn-group-sm",
            Option.when(!settled)(
              button(
                cls := "btn btn-outline-success btn-sm py-0",
                if line.isIncome then "Receive" else "Pay",
                onClick --> { _ => payingItemId.set(Some(d.id)) },
              ),
            ),
            button(cls := "btn btn-outline-secondary btn-sm py-0", "Edit", onClick --> { _ => editingItemId.set(Some(d.id)) }),
            Option.when(settled || partial)(
              Loading.actionButton(reset, () => dataService.resetBudgetItemPayment(d.id), "btn btn-outline-warning btn-sm py-0"),
            ),
          ),
        )
    }
  }

  /** The editor open on this entry, if any — narrowed and deduped per entry, so opening one doesn't re-render the rest of the list. */
  private def openEditor(line: Line): Signal[Option[HtmlElement]] =
    line.entry match {
      case Entry.Budget(s, _)    =>
        editingOverrideId.signal.map(_.contains(s.category.id)).distinct.map(Option.when(_)(overrideForm(line, s)))
      case Entry.Item(d, record) =>
        payingItemId.signal
          .combineWith(editingItemId.signal)
          .map { case (paying, editing) => (paying.contains(d.id), editing.contains(d.id)) }
          .distinct
          .map {
            case (true, _) => Some(payForm(line, d))
            case (_, true) => Some(editForm(d))
            case _         => None
          }
    }

  // ---- Inline editors -------------------------------------------------------------------------

  /** Payment editor for a manual entry. The amount ADDS to what's already paid, so it defaults to the remainder: "Pay" settles the entry at that
    * amount (closing it even if it came in under estimate), "Part" banks the instalment and leaves the rest outstanding.
    */
  private def payForm(line: Line, item: BudgetItemDefinition): HtmlElement = {
    var inputRef: org.scalajs.dom.html.Input = null

    def pay(settle: Boolean): () => Future[Unit] =
      () => dataService.payBudgetItem(item.id, parseCents(inputRef), settle).map(_ => payingItemId.set(None))

    val settleAction = Loading.actionGroup(if line.isIncome then "Receive" else "Pay", pay(settle = true), "btn btn-success btn-sm py-0")

    editorRow(
      if line.isIncome then "Received now" else "Paying now",
      moneyInput(Some(line.remaining), ref => inputRef = ref, autoFocus = true).amend(settleAction.onEnter),
      settleAction.btn,
      Loading.actionButton("Part", pay(settle = false), "btn btn-outline-success btn-sm py-0"),
      cancelButton(() => payingItemId.set(None)),
    )
  }

  private def editForm(item: BudgetItemDefinition): HtmlElement = {
    var nameRef: org.scalajs.dom.html.Input     = null
    var estimateRef: org.scalajs.dom.html.Input = null

    div(
      cls := "d-flex align-items-center gap-2 mt-1",
      div(styleAttr := "max-width: 14rem", textInput(item.name, ref => nameRef = ref)),
      div(styleAttr := "width: 7rem", moneyInput(Some(item.estimateCents), ref => estimateRef = ref, autoFocus = true)),
      div(
        cls         := "btn-group btn-group-sm",
        Loading.actionButton(
          "Save",
          () => dataService.updateBudgetItemEstimate(item.id, parseCents(estimateRef), item.currency).map(_ => editingItemId.set(None)),
          "btn btn-success btn-sm py-0",
        ),
        Loading.actionButton(
          "Del",
          () => dataService.deleteBudgetItem(item.id).map(_ => editingItemId.set(None)),
          "btn btn-outline-danger btn-sm py-0",
        ),
        cancelButton(() => editingItemId.set(None)),
      ),
    )
  }

  /** Inline editor for a budget's remaining amount, for this period only. It takes a MAGNITUDE and re-applies the budget's direction on the way out,
    * so an income budget is adjusted by saying how much is still expected to arrive rather than by typing a negative number.
    */
  private def overrideForm(line: Line, s: CategorySummary): HtmlElement = {
    var amountRef: org.scalajs.dom.html.Input = null

    val saveAction = Loading.actionGroup("Save", setOverride(s, parseCents(amountRef)), "btn btn-success btn-sm py-0")

    editorRow(
      if line.isIncome then "Still expected" else "Remaining",
      moneyInput(Some(line.remaining), ref => amountRef = ref, autoFocus = true).amend(saveAction.onEnter),
      List(
        Some(saveAction.btn),
        // The shortcut for "nothing more this period", which is all an override of zero means.
        Some(Loading.actionButton(if line.isIncome then "Received" else "Paid", setOverride(s, 0L), "btn btn-outline-success btn-sm py-0")),
        Option.when(s.overrideRemainingCents.isDefined)(
          Loading.actionButton(
            "Clear",
            () => dataService.clearCategoryBudgetOverride(s.category.id).map(_ => editingOverrideId.set(None)),
            "btn btn-outline-warning btn-sm py-0",
          ),
        ),
        Some(cancelButton(() => editingOverrideId.set(None))),
      ).flatten*,
    )
  }

  private def addForm(income: Boolean, currency: Currency): HtmlElement = {
    var nameRef: org.scalajs.dom.html.Input     = null
    var estimateRef: org.scalajs.dom.html.Input = null
    val itemType                                = if income then BudgetItemType.PlannedIncome else BudgetItemType.PlannedExpense
    val addingVar                               = if income then addingIncome else addingExpense

    val addAction = Loading.actionGroup(
      "Add",
      () => {
        val name = Option(nameRef).map(_.value.trim).getOrElse("")
        if name.nonEmpty then dataService.addBudgetItem(name, itemType, parseCents(estimateRef), currency).map(_ => addingVar.set(false))
        else Future.successful(())
      },
      "btn btn-success btn-sm py-0",
    )

    div(
      cls    := "d-flex align-items-center gap-2 mb-3",
      idAttr := (if income then "plan-add-income" else "plan-add-expense"),
      div(
        styleAttr := "max-width: 14rem",
        textInput(
          "",
          ref => nameRef = ref,
          placeholderText = if income then "Income name" else "Expense name",
          autoFocus = true,
        ).amend(addAction.onEnter),
      ),
      div(
        styleAttr := "width: 7rem",
        moneyInput(None, ref => estimateRef = ref, placeholderText = if income then "Expected" else "Estimate").amend(addAction.onEnter),
      ),
      div(cls     := "btn-group btn-group-sm", addAction.btn, cancelButton(() => addingVar.set(false))),
    )
  }

  /** The shape every inline editor shares: a label, one amount input, and its buttons. */
  private def editorRow(label: String, amountInput: HtmlElement, buttons: HtmlElement*): HtmlElement =
    div(
      cls := "d-flex align-items-center gap-2 mt-1",
      span(cls      := "small text-muted text-nowrap", label),
      div(styleAttr := "width: 7rem", amountInput),
      div(cls       := "btn-group btn-group-sm", buttons.toList),
    )

  private def cancelButton(onCancel: () => Unit): HtmlElement =
    button(tpe := "button", cls := "btn btn-secondary btn-sm py-0", "Cancel", onClick --> { _ => onCancel() })

  private def setOverride(s: CategorySummary, magnitude: => Long): () => Future[Unit] =
    // The stored override is signed (negative = still expected to arrive); the user only ever supplies a magnitude.
    () => dataService.setCategoryBudgetOverride(s.category.id, magnitude * s.direction).map(_ => editingOverrideId.set(None))

  // ---- Drill-down -----------------------------------------------------------------------------

  private def rowExpanded(categoryId: CategoryId): Signal[Boolean] = budgetTxs.signal.map(_.contains(categoryId)).distinct

  /** Expand/collapse a category entry's transaction list, fetching on expand. Collapsing drops the cached rows, so re-expanding always shows current
    * data (categories get recategorized on the Transactions page while this page stays open).
    */
  private def toggleBudgetExpansion(categoryId: CategoryId): Unit = {
    if budgetTxs.now().contains(categoryId) then budgetTxs.update(_ - categoryId)
    else {
      budgetTxs.update(_.updated(categoryId, LoadingState.Loading))
      // Only ever fill in an entry that's still expanded: collapsing mid-flight drops the key, and the late response mustn't put it back.
      def complete(state: LoadingState[TransactionListResponse]): Unit =
        budgetTxs.update(m => if m.contains(categoryId) then m.updated(categoryId, state) else m)
      dataService.categoryPeriodTransactions(categoryId, drillDownRows + 1).onComplete {
        case Success(page) => complete(LoadingState.Loaded(page))
        case Failure(ex)   => complete(LoadingState.Error(ex.getMessage))
      }
    }
  }

  /** The current period's transactions behind a category entry's spend: compact and read-only. Fixing a miscategorized transaction happens on the
    * Transactions page, which the link opens with this category and window already filtered.
    */
  private def drillDown(s: CategorySummary, state: LoadingState[TransactionListResponse]): HtmlElement = {
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
          val shown = page.items.take(drillDownRows)
          div(
            shown.map(transactionLine),
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

  private def transactionLine(t: BankTransaction): HtmlElement = {
    // Everything here is spend, so outflows stay neutral; an inflow (refund) is called out because it SUBTRACTS from the category's spend.
    val amountCls = if t.amountCents < 0 then "" else "text-success"
    div(
      cls := "d-flex gap-2 small",
      span(cls := "text-muted text-nowrap font-monospace", Formatting.formatDateShort(t.bookedAt)),
      span(cls := "text-truncate flex-grow-1", title := t.description, t.description),
      span(cls := s"font-monospace text-nowrap $amountCls", MoneyFormatter.formatSimple(t.amountCents, t.currency)),
    )
  }

  // ---- Shared bits ----------------------------------------------------------------------------

  private def money(cents: Long): String = MoneyFormatter.formatSimple(cents, MoneyFormatter.primary)

  /** What the plan still expects to move, in both directions — the two terms the free-money figure is waiting on, across both kinds of entry. */
  private def footer(): HtmlElement =
    div(
      cls := "card-footer py-2",
      div(
        cls := "d-flex justify-content-between mb-1",
        span(cls := "text-muted small", "Still to pay"),
        span(cls := "font-monospace small", MoneyFormatter.formatChild(dataService.stillToPay)),
      ),
      div(
        cls := "d-flex justify-content-between",
        span(cls := "text-muted small", "Still to receive"),
        span(cls := "font-monospace small", MoneyFormatter.formatChild(dataService.stillToReceive)),
      ),
      // Worth saying only while nothing is transaction-derived yet: that's the mechanism to reach for first.
      child.maybe <-- dataService.budgetedCategories.map { budgets =>
        Option.when(budgets.isEmpty)(
          div(
            cls := "small text-muted mt-1",
            "No category budgets yet — set a budget type (Steady / Bill / Subscription) on a category on the Transactions page to have it predicted " +
              "from your transactions.",
          ),
        )
      },
    )
}
