package ssbudget.frontend.pages

import com.raquo.laminar.api.L.*
import io.circe.syntax.*
import org.scalajs.dom
import ssbudget.frontend.components.{CategoryCombobox, RuleModal}
import ssbudget.frontend.services.ApiClient
import ssbudget.frontend.util.{Formatting, MoneyFormatter}
import ssbudget.shared.api.{
  BankConnectionView,
  CategorySummary,
  CreateCategory,
  ImportRulesRequest,
  RulesExport,
  SetCategoryRequest,
  SetNoteRequest,
  UpdateCategory,
}
import ssbudget.shared.model.{BankTransaction, Category, CategoryId, ClassificationRule, ClassificationRuleId, Money, TransactionStatus}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js
import scala.util.{Failure, Success}

object TransactionsPage {

  def apply(apiClient: ApiClient): HtmlElement = {
    val txVar          = Var(List.empty[BankTransaction]) // current page (server-capped)
    val totalVar       = Var(0)                           // total matching the filters, before the cap
    val sumsVar        = Var(List.empty[Money])           // net sum per currency over the FULL match (not just the page)
    val catsVar        = Var(List.empty[Category])
    val summariesVar   = Var(List.empty[CategorySummary])
    val rulesVar       = Var(List.empty[ClassificationRule])
    val connsVar       = Var(List.empty[BankConnectionView])
    val monthsVar      = Var(List.empty[String])
    val loadingVar     = Var(true)
    val errorVar       = Var(Option.empty[String])
    val monthFilter    = Var("all")
    val accountFilter  = Var("")
    val categoryFilter = Var("uncategorized")             // triage-first: default to what still needs a category
    val hideInternal   = Var(true)                        // internal transfers between own accounts are hidden by default
    val sortBy         = Var("date")                      // "date" | "amount"
    val sortAsc        = Var(false)                       // default: date descending (newest first)
    val ruleModalState = Var(Option.empty[RuleModal.Seed])

    def loadCategories(): Unit =
      apiClient.categories.list().onComplete {
        case Success(c)  => catsVar.set(c)
        case Failure(ex) => errorVar.set(Some(s"Failed to load categories: ${ex.getMessage}"))
      }

    def loadSummaries(): Unit =
      apiClient.categories.summaries().onComplete {
        case Success(s) => summariesVar.set(s)
        case Failure(_) => () // averages are informational; ignore failures
      }

    def loadRules(): Unit =
      apiClient.rules.list().onComplete {
        case Success(r)  => rulesVar.set(r)
        case Failure(ex) => errorVar.set(Some(s"Failed to load rules: ${ex.getMessage}"))
      }

    def loadConnections(): Unit =
      apiClient.banking.connections().onComplete {
        case Success(c) => connsVar.set(c)
        case Failure(_) => () // connections drive the account dropdown + friendly labels; ignore failures
      }

    def loadMonths(): Unit =
      apiClient.transactions.months().onComplete {
        case Success(m) => monthsVar.set(m)
        case Failure(_) => () // month dropdown is a convenience; ignore failures
      }

    // All filtering/sorting/capping is server-side — read the current filter Vars and fetch a fresh page.
    def loadTransactions(): Unit = {
      loadingVar.set(true)
      apiClient.transactions
        .query(
          accountUid = Some(accountFilter.now()).filter(_.nonEmpty),
          month = Some(monthFilter.now()).filter(_ != "all"),
          category = Some(categoryFilter.now()), // "all" | "uncategorized" | categoryId — server interprets
          hideInternal = hideInternal.now(),
          sort = sortBy.now(),
          asc = sortAsc.now(),
          limit = None,                          // server applies its display cap
        )
        .onComplete {
          case Success(r)  => txVar.set(r.items); totalVar.set(r.total); sumsVar.set(r.sums); loadingVar.set(false)
          case Failure(ex) => errorVar.set(Some(s"Failed to load transactions: ${ex.getMessage}")); loadingVar.set(false)
        }
    }

    // Any filter/sort change re-fetches. Binding this signal also fires on mount → the initial load.
    val filtersTrigger: Signal[?] =
      monthFilter.signal
        .combineWith(accountFilter.signal)
        .combineWith(categoryFilter.signal)
        .combineWith(hideInternal.signal)
        .combineWith(sortBy.signal)
        .combineWith(sortAsc.signal)

    def setCategory(txId: ssbudget.shared.model.BankTransactionId, categoryId: Option[CategoryId]): Unit =
      apiClient.transactions.setCategory(txId, SetCategoryRequest(categoryId)).onComplete {
        case Success(updated) => txVar.update(_.map(t => if t.id == updated.id then updated else t))
        case Failure(ex)      => errorVar.set(Some(s"Failed to set category: ${ex.getMessage}"))
      }

    def setNote(txId: ssbudget.shared.model.BankTransactionId, note: Option[String]): Unit =
      apiClient.transactions.setNote(txId, SetNoteRequest(note)).onComplete {
        case Success(updated) => txVar.update(_.map(t => if t.id == updated.id then updated else t))
        case Failure(ex)      => errorVar.set(Some(s"Failed to save note: ${ex.getMessage}"))
      }

    div(
      cls := "container-fluid mt-3",
      onMountCallback { _ =>
        loadCategories()
        loadSummaries()
        loadRules()
        loadConnections()
        loadMonths()
      },
      filtersTrigger --> Observer(_ => loadTransactions()),
      div(
        cls := "d-flex justify-content-between align-items-center mb-3",
        h4(cls    := "mb-0", "Transactions"),
        small(cls := "text-muted", "Import transactions from the Banking page."),
      ),
      child.maybe <-- errorVar.signal.map(_.map { e =>
        div(cls := "alert alert-danger alert-dismissible", e, button(tpe := "button", cls := "btn-close", onClick --> { _ => errorVar.set(None) }))
      }),
      categoriesCard(catsVar, summariesVar.signal, apiClient, loadCategories, loadSummaries, () => loadTransactions()),
      rulesCard(
        rulesVar,
        catsVar.signal,
        apiClient,
        loadRules,
        () => loadTransactions(),
        () => { loadRules(); loadCategories(); loadSummaries(); loadTransactions() },
        seed => ruleModalState.set(Some(seed)),
      ),
      filtersRow(monthsVar.signal, connsVar.signal, catsVar.signal, monthFilter, accountFilter, categoryFilter, hideInternal),
      transactionsTable(
        txVar.signal,
        totalVar.signal,
        sumsVar.signal,
        catsVar.signal,
        connsVar.signal,
        sortBy,
        sortAsc,
        loadingVar.signal,
        setCategory,
        setNote,
        tx => ruleModalState.set(Some(RuleModal.fromTransaction(tx))),
        apiClient,
        () => { loadCategories(); loadSummaries() },
      ),
      RuleModal(
        ruleModalState,
        catsVar.signal,
        apiClient,
        () => { loadRules(); loadTransactions() },
        loadCategories,
      ),
    )
  }

  private def accountLabel(conns: List[BankConnectionView], uid: String): String =
    conns.flatMap(_.accounts).find(_.ebAccountUid == uid).flatMap(l => l.name.orElse(l.product)).getOrElse(uid.take(8) + "…")

  /** Trigger a browser download of `content` as `filename` (used to save the rules export as a JSON file). */
  private def downloadJson(filename: String, content: String): Unit = {
    val blob = new dom.Blob(js.Array(content.asInstanceOf[dom.BlobPart]))
    val url  = dom.URL.createObjectURL(blob)
    val a    = dom.document.createElement("a").asInstanceOf[dom.html.Anchor]
    a.setAttribute("href", url)
    a.setAttribute("download", filename)
    dom.document.body.appendChild(a)
    a.click()
    dom.document.body.removeChild(a)
    dom.URL.revokeObjectURL(url)
  }

  private def filtersRow(
      months: Signal[List[String]],
      conns: Signal[List[BankConnectionView]],
      cats: Signal[List[Category]],
      monthFilter: Var[String],
      accountFilter: Var[String],
      categoryFilter: Var[String],
      hideInternal: Var[Boolean],
  ): HtmlElement =
    div(
      cls := "row g-2 align-items-end mb-2",
      div(
        cls := "col-auto",
        label(cls := "form-label small mb-1", "Category"),
        select(
          cls     := "form-select form-select-sm",
          value <-- categoryFilter.signal,
          onChange.mapToValue --> categoryFilter.writer,
          option(value := "uncategorized", "Uncategorized"),
          option(value := "all", "All categories"),
          children <-- cats.map(_.map(c => option(value := c.id.value, c.name))),
        ),
      ),
      div(
        cls := "col-auto",
        div(
          cls := "form-check",
          input(
            cls     := "form-check-input",
            tpe     := "checkbox",
            idAttr  := "hideInternal",
            checked <-- hideInternal.signal,
            onChange.mapToChecked --> hideInternal.writer,
          ),
          label(cls := "form-check-label small", forId := "hideInternal", "Hide internal transfers"),
        ),
      ),
      div(
        cls := "col-auto",
        label(cls := "form-label small mb-1", "Month"),
        select(
          cls     := "form-select form-select-sm",
          value <-- monthFilter.signal,
          onChange.mapToValue --> monthFilter.writer,
          option(value := "all", "All months"),
          option(value := "current-period", "Current period"),
          children <-- months.map(_.map(m => option(value := m, m))),
        ),
      ),
      div(
        cls := "col-auto",
        label(cls := "form-label small mb-1", "Account"),
        select(
          cls     := "form-select form-select-sm",
          value <-- accountFilter.signal,
          onChange.mapToValue --> accountFilter.writer,
          option(value := "", "All accounts"),
          children <-- conns.map { cs =>
            cs.flatMap(_.accounts).map(_.ebAccountUid).distinct.map(uid => option(value := uid, accountLabel(cs, uid)))
          },
        ),
      ),
    )

  private def transactionsTable(
      txs: Signal[List[BankTransaction]],
      total: Signal[Int],
      sums: Signal[List[Money]],
      cats: Signal[List[Category]],
      conns: Signal[List[BankConnectionView]],
      sortBy: Var[String],
      sortAsc: Var[Boolean],
      loading: Signal[Boolean],
      setCategory: (ssbudget.shared.model.BankTransactionId, Option[CategoryId]) => Unit,
      setNote: (ssbudget.shared.model.BankTransactionId, Option[String]) => Unit,
      onCreateRule: BankTransaction => Unit,
      apiClient: ApiClient,
      onCategoryCreated: () => Unit,
  ): HtmlElement = {
    // Click a sortable header: toggle direction if it's the active column, else switch to it (descending first). Re-sorting re-fetches server-side.
    def onHeader(key: String): Unit =
      if sortBy.now() == key then sortAsc.update(!_) else { sortBy.set(key); sortAsc.set(false) }

    def arrow(key: String): Signal[String] =
      sortBy.signal.combineWith(sortAsc.signal).map { case (k, asc) => if k == key then (if asc then " ▲" else " ▼") else "" }

    def sortableTh(key: String, label: String, extraCls: String): HtmlElement =
      th(
        cls       := s"user-select-none $extraCls",
        styleAttr := "cursor: pointer",
        onClick --> { _ => onHeader(key) },
        label,
        child.text <-- arrow(key),
      )

    div(
      cls := "card",
      div(
        cls := "card-body p-0",
        table(
          cls := "table table-sm table-hover mb-0",
          thead(
            tr(
              sortableTh("date", "Date", ""),
              th("Account"),
              th("Description"),
              sortableTh("amount", "Amount", "text-end"),
              th("Status"),
              th("Category"),
            ),
          ),
          tbody(
            child <-- loading.map(l => if l then tr(td(colSpan := 6, cls := "text-center py-3", "Loading…")) else emptyNode),
            // Rows depend only on txs + conns; the per-row category combobox subscribes to `cats` itself, so creating/renaming a category
            // doesn't rebuild every row.
            children <-- txs
              .combineWith(conns)
              .map { case (ts, cs) =>
                ts.map(t => transactionRow(t, cats, cs, setCategory, setNote, onCreateRule, apiClient, onCategoryCreated))
              },
          ),
        ),
      ),
      div(
        cls := "card-footer py-2 d-flex justify-content-between align-items-center small",
        span(
          cls := "text-muted",
          child.text <-- txs.combineWith(total).map { case (ts, tot) =>
            if ts.size < tot then s"showing ${ts.size} of $tot — narrow the filters to see more"
            else s"${ts.size} transactions"
          },
        ),
        // Net sum over ALL matching rows (not just the shown page), per currency.
        span(
          cls := "fw-semibold",
          child.text <-- sums.map { ss =>
            if ss.isEmpty then "" else "Sum: " + ss.map(m => MoneyFormatter.formatSimple(m.amountCents, m.currency)).mkString("   ")
          },
        ),
      ),
    )
  }

  private def transactionRow(
      t: BankTransaction,
      cats: Signal[List[Category]],
      conns: List[BankConnectionView],
      setCategory: (ssbudget.shared.model.BankTransactionId, Option[CategoryId]) => Unit,
      setNote: (ssbudget.shared.model.BankTransactionId, Option[String]) => Unit,
      onCreateRule: BankTransaction => Unit,
      apiClient: ApiClient,
      onCategoryCreated: () => Unit,
  ): HtmlElement = {
    val amountCls   = if t.amountCents < 0 then "text-danger" else "text-success"
    val description = t.counterpartyName.orElse(t.remittance).getOrElse(t.bankTransactionCode.getOrElse("—"))
    val statusBadge = t.status match {
      case TransactionStatus.Booked  => span(cls := "badge text-bg-light text-muted", "booked")
      case TransactionStatus.Pending => span(cls := "badge text-bg-warning", "pending")
    }

    // Inline-editable note. The row is rebuilt whenever the tx list changes, so a save (which updates the row in place) closes the editor and
    // re-renders with the fresh note; `editingNote`/`draft` are fresh per rebuild.
    val editingNote        = Var(false)
    val draft              = Var(t.note.getOrElse(""))
    def commitNote(): Unit = {
      val cleaned = Some(draft.now().trim).filter(_.nonEmpty)
      editingNote.set(false)
      if cleaned != t.note then setNote(t.id, cleaned) // skip a no-op write (also makes Escape-then-blur harmless)
    }
    val noteBlock          = child <-- editingNote.signal.map {
      case true  =>
        input(
          cls         := "form-control form-control-sm mt-1",
          placeholder := "Note…",
          controlled(value <-- draft.signal, onInput.mapToValue --> draft.writer),
          onMountFocus,
          onBlur --> { _ => commitNote() },
          onKeyDown --> { e =>
            if e.key == "Enter" then commitNote()
            else if e.key == "Escape" then { draft.set(t.note.getOrElse("")); editingNote.set(false) }
          },
        )
      case false =>
        t.note match {
          case Some(n) =>
            div(
              cls       := "small fst-italic text-body-secondary",
              styleAttr := "cursor:pointer",
              title     := "Click to edit note",
              onClick --> { _ => editingNote.set(true) },
              "💬 ",
              n,
            )
          case None    =>
            a(
              cls  := "small text-muted",
              href := "#",
              onClick.preventDefault --> { _ => editingNote.set(true) },
              "＋ note",
            )
        }
    }

    tr(
      td(cls      := "text-muted small text-nowrap", Formatting.formatDate(t.bookedAt)),
      td(cls      := "small", accountLabel(conns, t.ebAccountUid)),
      td(
        div(description, if t.internal then span(cls := "badge text-bg-light text-muted ms-2", "internal") else emptyNode),
        t.remittance.filter(r => !t.counterpartyName.contains(r)).map(r => small(cls := "text-muted d-block", r)).getOrElse(emptyNode),
        noteBlock,
      ),
      td(cls      := s"text-end font-monospace $amountCls", MoneyFormatter.formatSimple(t.amountCents, t.currency)),
      td(statusBadge),
      td(
        styleAttr := "min-width: 12rem",
        div(
          cls := "d-flex align-items-center gap-1",
          div(
            cls   := "flex-grow-1",
            CategoryCombobox(
              cats = cats,
              selectedId = Val(t.categoryId),
              onSelect = opt => setCategory(t.id, opt),
              apiClient = apiClient,
              onCreated = onCategoryCreated,
              allowClear = true,
              placeholderText = "Category…",
            ),
          ),
          if t.categorySource.contains(ssbudget.shared.model.CategorySource.Rule) then span(cls := "badge text-bg-info", "rule") else emptyNode,
          button(
            tpe   := "button",
            cls   := "btn btn-sm btn-outline-secondary text-nowrap",
            title := "Create a categorization rule from this transaction",
            onClick --> { _ => onCreateRule(t) },
            "+ rule",
          ),
        ),
      ),
    )
  }

  private def categoriesCard(
      catsVar: Var[List[Category]],
      summaries: Signal[List[CategorySummary]],
      apiClient: ApiClient,
      reloadCategories: () => Unit,
      reloadSummaries: () => Unit,
      reloadTransactions: () => Unit,
  ): HtmlElement = {
    val nameVar   = Var("")
    val editingId = Var(Option.empty[CategoryId]) // category whose name is being edited inline
    val editName  = Var("")

    def addCategory(): Unit = {
      val name = nameVar.now().trim
      if name.nonEmpty then apiClient.categories.create(CreateCategory(name, None)).onComplete {
        case Success(_) => nameVar.set(""); reloadCategories(); reloadSummaries()
        case Failure(_) => ()
      }
    }

    def renameCategory(c: Category): Unit = {
      val name = editName.now().trim
      editingId.set(None)
      if name.nonEmpty && name != c.name then apiClient.categories.update(c.id, UpdateCategory(name, c.color, c.monthlyBudget)).onComplete {
        case Success(_) => reloadCategories(); reloadSummaries() // name shows on the tx category dropdown + rules card via cats
        case Failure(_) => ()
      }
    }

    def deleteCategory(id: CategoryId): Unit =
      apiClient.categories.delete(id).onComplete {
        case Success(_) => reloadCategories(); reloadSummaries(); reloadTransactions() // transactions lose the cleared category
        case Failure(_) => ()
      }

    def toggleBudget(c: Category): Unit =
      apiClient.categories.update(c.id, UpdateCategory(c.name, c.color, !c.monthlyBudget)).onComplete {
        case Success(_) => reloadCategories(); reloadSummaries()
        case Failure(_) => ()
      }

    // Row per category: rolling 3-mo average + this-period spend + the "monthly budget" toggle that surfaces it on the budget page.
    def categoryRow(c: Category, summary: Option[CategorySummary]): HtmlElement = {
      val currency                   = summary.map(_.currency)
      def money(cents: Long): String =
        currency.map(cur => MoneyFormatter.formatSimple(cents, cur)).getOrElse("—")
      tr(
        td(
          child <-- editingId.signal.map { editing =>
            if editing.contains(c.id) then input(
              cls         := "form-control form-control-sm",
              controlled(value <-- editName.signal, onInput.mapToValue --> editName.writer),
              onBlur --> { _ => renameCategory(c) },
              onKeyDown.filter(_.key == "Enter") --> { _ => renameCategory(c) },
              onKeyDown.filter(_.key == "Escape") --> { _ => editingId.set(None) },
              onMountCallback(ctx => ctx.thisNode.ref.focus()),
            )
            else
              span(
                styleAttr := "cursor: pointer",
                title     := "Click to rename",
                onClick --> { _ => editName.set(c.name); editingId.set(Some(c.id)) },
                c.name,
              )
          },
        ),
        td(cls := "text-end font-monospace small", summary.map(s => money(s.avgMonthlyCents)).getOrElse("—")),
        td(cls := "text-end font-monospace small", summary.map(s => money(s.currentPeriodSpentCents)).getOrElse("—")),
        td(
          cls  := "text-center",
          input(
            tpe     := "checkbox",
            cls     := "form-check-input",
            checked := c.monthlyBudget,
            onChange.mapToChecked --> { _ => toggleBudget(c) },
          ),
        ),
        td(
          cls  := "text-end",
          button(
            tpe       := "button",
            cls       := "btn-close",
            styleAttr := "font-size: 0.6rem",
            title     := "Delete category",
            onClick --> { _ => deleteCategory(c.id) },
          ),
        ),
      )
    }

    div(
      cls := "card mb-3",
      div(cls := "card-header py-2", "Categories & monthly averages"),
      div(
        cls   := "card-body p-0",
        table(
          cls := "table table-sm table-hover mb-0 align-middle",
          thead(
            tr(
              th("Category"),
              th(
                cls   := "text-end",
                "Avg / mo",
                title := "Median monthly spend over the last 3 full calendar months (robust to a month with two payments)",
              ),
              th(cls  := "text-end", "This period"),
              th(cls  := "text-center", "Budget", title := "Track as a monthly budget on the Budget page"),
              th(),
            ),
          ),
          tbody(
            children <-- catsVar.signal.combineWith(summaries).map { case (cats, summs) =>
              if cats.isEmpty then List(tr(td(colSpan := 5, cls := "text-muted small text-center py-2", "No categories yet.")))
              else {
                val byId = summs.map(s => s.category.id -> s).toMap
                cats.map(c => categoryRow(c, byId.get(c.id)))
              }
            },
          ),
        ),
      ),
      div(
        cls   := "card-footer py-2",
        div(
          cls       := "input-group input-group-sm",
          styleAttr := "max-width: 24rem",
          input(
            cls         := "form-control",
            placeholder := "New category (e.g. Groceries)",
            controlled(value <-- nameVar.signal, onInput.mapToValue --> nameVar.writer),
            onKeyDown.filter(_.key == "Enter") --> { _ => addCategory() },
          ),
          button(cls    := "btn btn-outline-primary", "Add", onClick --> { _ => addCategory() }),
        ),
      ),
    )
  }

  private def rulesCard(
      rulesVar: Var[List[ClassificationRule]],
      cats: Signal[List[Category]],
      apiClient: ApiClient,
      reloadRules: () => Unit,
      reloadTransactions: () => Unit,
      onImported: () => Unit, // full refresh after an import (rules + categories + summaries + tx)
      onEdit: RuleModal.Seed => Unit,
  ): HtmlElement = {
    val expanded = Var(false) // collapsed by default; the rules card is for occasional management, keep the triage view compact

    def move(id: ClassificationRuleId, delta: Int): Unit = {
      val ids = rulesVar.now().map(_.id)
      val idx = ids.indexOf(id)
      val j   = idx + delta
      if idx >= 0 && j >= 0 && j < ids.size then {
        val reordered = ids.toBuffer
        val tmp       = reordered(idx); reordered(idx) = reordered(j); reordered(j) = tmp
        apiClient.rules.reorder(reordered.toList).onComplete {
          case Success(rs) => rulesVar.set(rs); reloadTransactions()
          case Failure(_)  => ()
        }
      }
    }

    def delete(id: ClassificationRuleId): Unit =
      apiClient.rules.delete(id).onComplete {
        case Success(_) => reloadRules(); reloadTransactions()
        case Failure(_) => ()
      }

    def reapply(): Unit =
      apiClient.rules.apply().onComplete {
        case Success(_) => reloadTransactions()
        case Failure(_) => ()
      }

    def exportRules(): Unit =
      apiClient.rules.exportRules().onComplete {
        case Success(bundle) => downloadJson("ssbudget-rules.json", bundle.asJson.spaces2)
        case Failure(ex)     => dom.window.alert(s"Export failed: ${ex.getMessage}")
      }

    def importFile(replace: Boolean, file: dom.File): Unit = {
      val reader = new dom.FileReader()
      reader.onload = _ =>
        io.circe.parser.decode[RulesExport](reader.result.asInstanceOf[String]) match {
          case Right(bundle) =>
            apiClient.rules.importRules(ImportRulesRequest(replace, bundle)).onComplete {
              case Success(res) =>
                onImported()
                dom.window.alert(
                  s"Imported ${res.rulesImported} rule(s); created ${res.categoriesCreated} new categor${if res.categoriesCreated == 1 then "y"
                    else "ies"}.",
                )
              case Failure(ex)  => dom.window.alert(s"Import failed: ${ex.getMessage}")
            }
          case Left(err)     => dom.window.alert(s"Invalid rules file: ${err.getMessage}")
        }
      reader.readAsText(file)
    }

    // Hidden file picker; the "Import" button wires its onchange and triggers it. Confirm asks whether to replace or merge.
    val fileInput = input(tpe := "file", accept := ".json,application/json", styleAttr := "display: none")

    div(
      cls := "card mb-3",
      fileInput,
      div(
        cls := "card-header py-2 d-flex justify-content-between align-items-center",
        span(
          cls       := "user-select-none",
          styleAttr := "cursor: pointer; flex-grow: 1",
          onClick --> { _ => expanded.update(!_) },
          child.text <-- expanded.signal.map(e => if e then "▾ " else "▸ "),
          "Categorization rules",
          child.text <-- rulesVar.signal.map(rs => if rs.isEmpty then "" else s" (${rs.size})"),
        ),
        div(
          cls       := "btn-group btn-group-sm",
          button(tpe := "button", cls := "btn btn-outline-secondary", "Re-apply", onClick --> { _ => reapply() }),
          button(tpe := "button", cls := "btn btn-outline-secondary", "Export", onClick --> { _ => exportRules() }),
          button(
            tpe      := "button",
            cls      := "btn btn-outline-secondary",
            "Import",
            onClick --> { _ =>
              val el = fileInput.ref
              el.onchange = _ => {
                val files = el.files
                if files != null && files.length > 0 then {
                  val replace = dom.window.confirm("Replace all existing rules with the imported ones?\n\nOK = replace · Cancel = merge (append).")
                  importFile(replace, files(0))
                }
                el.value = "" // let the same file be picked again next time
              }
              el.click()
            },
          ),
        ),
      ),
      child.maybe <-- expanded.signal.map { e =>
        Option.when(e)(
          div(
            cls := "card-body py-2",
            children <-- rulesVar.signal.combineWith(cats).map { case (rules, categories) =>
              if rules.isEmpty then List(div(cls := "text-muted small", "No rules yet. Use “+ rule” on a transaction to create one."))
              else {
                val catName = categories.map(c => c.id -> c.name).toMap
                rules.zipWithIndex.map { case (rule, i) =>
                  div(
                    cls := "d-flex align-items-center gap-2 py-1 border-bottom",
                    div(
                      cls    := "btn-group btn-group-sm",
                      button(tpe := "button", cls := "btn btn-outline-secondary", disabled := (i == 0), "↑", onClick --> { _ => move(rule.id, -1) }),
                      button(
                        tpe      := "button",
                        cls      := "btn btn-outline-secondary",
                        disabled := (i == rules.size - 1),
                        "↓",
                        onClick --> { _ => move(rule.id, 1) },
                      ),
                    ),
                    span(cls := "fw-semibold small", rule.name),
                    span(cls := "badge text-bg-primary", catName.getOrElse(rule.categoryId, "?")),
                    div(
                      cls    := "d-flex flex-wrap gap-1",
                      rule.criteria.map(c => span(cls := "badge text-bg-light text-muted", RuleModal.describe(c))),
                    ),
                    div(
                      cls    := "ms-auto d-flex gap-1",
                      button(
                        tpe      := "button",
                        cls      := "btn btn-sm btn-outline-secondary",
                        "Edit",
                        onClick --> { _ => onEdit(RuleModal.fromRule(rule)) },
                      ),
                      button(tpe := "button", cls := "btn btn-sm btn-outline-danger", "Delete", onClick --> { _ => delete(rule.id) }),
                    ),
                  )
                }
              }
            },
          ),
        )
      },
    )
  }
}
