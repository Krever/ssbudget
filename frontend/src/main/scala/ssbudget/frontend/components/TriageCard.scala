package ssbudget.frontend.components

import com.raquo.laminar.api.L.*
import ssbudget.frontend.services.{ApiClient, DataService}
import ssbudget.frontend.util.{Formatting, MoneyFormatter}
import ssbudget.frontend.{Page, Router}
import ssbudget.shared.model.{BankTransaction, Category, CategoryId}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

/** The uncategorized backlog, reduced to the one thing worth doing about it from the Dashboard: get a category onto the newest few, either by hand or
  * — the usual way — by writing the rule that categorizes them and everything like them. No filters, no sorting, no notes: those live on the
  * Transactions page, which the header links to.
  *
  * The card removes itself when the backlog is empty, so a fully triaged month costs the Dashboard nothing.
  */
object TriageCard {

  /** How many rows to show. Small on purpose: this is a nudge to keep up with triage, not a work queue. */
  private val PageSize = 6

  private val dataService = DataService.instance

  /** `apiClient` is only for the components that manage categories and rules in their own right (the combobox and the rule modal); everything this
    * card reads or writes itself goes through [[DataService]], so the figures above it stay in step.
    */
  def apply(apiClient: ApiClient): HtmlElement = {
    val txVar          = Var(List.empty[BankTransaction])
    val totalVar       = Var(0) // the whole backlog, of which the rows are the newest PageSize
    val ruleModalState = Var(Option.empty[RuleModal.Seed])

    // Every category, from the same load the rest of the Dashboard is drawn from.
    val cats: Signal[List[Category]] = dataService.categorySummaries.map(_.map(_.category))

    def loadTransactions(): Unit =
      dataService.uncategorizedTransactions(PageSize).onComplete {
        // One transaction, so the card renders its rows and its count together.
        case Success(r) => Var.set(txVar -> r.items, totalVar -> r.total)
        case Failure(_) => () // a failed load leaves the card as it was rather than showing an error on the Dashboard
      }

    // Drop the row straight away — it has left the backlog by definition — then refetch to pull the next one up and keep the count honest.
    def setCategory(t: BankTransaction, categoryId: CategoryId): Unit = {
      txVar.update(_.filterNot(_.id == t.id))
      // Either way: on success to backfill, on failure to put the row back. The server's answer decides, not the optimistic edit.
      dataService.setTransactionCategory(t.id, Some(categoryId)).onComplete(_ => loadTransactions())
    }

    def row(t: BankTransaction): HtmlElement =
      tr(
        td(cls      := "text-muted small text-nowrap", styleAttr := "width: 6.5rem", Formatting.formatDate(t.bookedAt)),
        // No width: it takes what the fixed layout leaves over, and truncates rather than wrapping a long bank description over three lines.
        td(
          title     := t.description,
          div(cls := "text-truncate", t.description),
          t.remittance.filter(r => !t.counterpartyName.contains(r)).map(r => small(cls := "text-muted d-block text-truncate", r)).getOrElse(emptyNode),
        ),
        td(
          cls       := s"text-end font-monospace text-nowrap ${if t.amountCents < 0 then "text-danger" else "text-success"}",
          styleAttr := "width: 7rem",
          MoneyFormatter.formatSimple(t.amountCents, t.currency),
        ),
        td(
          styleAttr := "width: 18rem",
          div(
            cls := "d-flex align-items-center gap-1",
            div(
              cls   := "flex-grow-1",
              CategoryCombobox(
                cats = cats,
                selectedId = Val(None),
                onSelect = _.foreach(id => setCategory(t, id)), // no clear button on an uncategorized row, so there is no None to handle
                apiClient = apiClient,
                onCreated = () => { dataService.initialize(); () },
                placeholderText = "Category…",
              ),
            ),
            // Rules are the primary way things get categorized, so the same one-click way in as on the Transactions page: seed a rule from this row.
            button(
              tpe   := "button",
              cls   := "btn btn-sm btn-outline-secondary text-nowrap",
              title := "Create a categorization rule from this transaction",
              onClick --> { _ => ruleModalState.set(Some(RuleModal.fromTransaction(t))) },
              "+ rule",
            ),
          ),
        ),
      )

    /** The card, built once per appearance rather than per change: the rows are keyed by transaction id, so categorizing one row leaves the other
      * rows' comboboxes (and anything half-typed into them) untouched.
      */
    def card: HtmlElement =
      div(
        cls := "card mb-3",
        div(
          cls := "card-header py-2 d-flex justify-content-between align-items-center",
          span("To categorize", child.text <-- totalVar.signal.map(n => s" ($n)")),
          a(
            cls := "small text-muted text-decoration-none",
            Router.linkTo(Page.Transactions()),
            "all transactions →",
          ),
        ),
        div(
          cls := "card-body p-0",
          // Fixed layout so the per-column widths above actually hold and the description truncates instead of squeezing the picker.
          table(
            cls       := "table table-sm table-hover align-middle mb-0",
            styleAttr := "table-layout: fixed",
            tbody(children <-- txVar.signal.split(_.id)((_, t, _) => row(t))),
          ),
        ),
      )

    div(
      onMountCallback { _ => loadTransactions() },
      // `distinct` so only appearing and disappearing rebuild the card; row changes are handled inside it.
      child.maybe <-- txVar.signal.map(_.nonEmpty).distinct.map(Option.when(_)(card)),
      // A saved rule categorizes this row and usually others with it, so the whole backlog is refetched rather than the one row dropped — and the
      // budgets those transactions now count towards are re-read with it.
      RuleModal(
        ruleModalState,
        cats,
        apiClient,
        () => { dataService.initialize(); loadTransactions() },
        () => { dataService.initialize(); () },
      ),
    )
  }
}
