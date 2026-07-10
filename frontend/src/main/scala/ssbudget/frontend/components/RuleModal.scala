package ssbudget.frontend.components

import com.raquo.laminar.api.L.*
import ssbudget.frontend.services.ApiClient
import ssbudget.frontend.util.MoneyFormatter
import ssbudget.shared.api.{CreateRuleRequest, RulePreviewResponse, UpdateRuleRequest}
import ssbudget.shared.model.*
import ssbudget.shared.rules.RuleMatcher

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

/** Modal for creating/editing a categorization rule. Seeded from a transaction (candidate criteria pre-filled, user trims) or from an existing rule.
  * Previews live which transactions it would cover before saving: the "covers this originating tx" check is client-side (single tx in hand), while
  * the "matches N of M" count is computed server-side (the browser no longer holds all transactions), debounced as the user edits criteria.
  */
object RuleModal {

  /** State to open the modal with. `criteria` pairs each candidate with whether it starts enabled. */
  final case class Seed(
      editingId: Option[ClassificationRuleId],
      name: String,
      categoryId: Option[CategoryId],
      criteria: List[(Boolean, RuleCriterion)],
      originTx: Option[BankTransaction], // the tx the rule is being built from, for the "covers this" check
  )

  /** Build a seed from a transaction: pre-enable counterparty-name-contains (or remittance fallback) + direction; leave the rest off. */
  def fromTransaction(tx: BankTransaction): Seed = {
    val hasName                            = tx.counterpartyName.exists(_.trim.nonEmpty)
    def enabled(c: RuleCriterion): Boolean = c match {
      case _: RuleCriterion.CounterpartyName => true
      case _: RuleCriterion.Remittance       => !hasName
      case _: RuleCriterion.Direction        => true
      case _                                 => false
    }
    val name                               = tx.counterpartyName.orElse(tx.remittance).map(_.trim).filter(_.nonEmpty).getOrElse("New rule")
    Seed(None, name, None, RuleMatcher.candidateCriteria(tx).map(c => (enabled(c), c)), Some(tx))
  }

  /** Build a seed from an existing rule (all criteria enabled). */
  def fromRule(rule: ClassificationRule): Seed =
    Seed(Some(rule.id), rule.name, Some(rule.categoryId), rule.criteria.map(c => (true, c)), None)

  /** Human-readable one-liner for a criterion (used in the modal and the rules card badges). */
  def describe(c: RuleCriterion): String = c match {
    case RuleCriterion.CounterpartyName(op, v)   => s"name ${opStr(op)} “$v”"
    case RuleCriterion.Remittance(op, v)         => s"remittance ${opStr(op)} “$v”"
    case RuleCriterion.CounterpartyAccount(iban) => s"IBAN = $iban"
    case RuleCriterion.BankTransactionCode(code) => s"code = $code"
    case RuleCriterion.Account(uid)              => s"account = ${uid.take(8)}…"
    case RuleCriterion.Direction(outflow)        => if outflow then "outflow" else "inflow"
    case RuleCriterion.AmountCompare(op, a)      => s"amount ${amountOpStr(op)} ${centsToStr(a)}"
    case RuleCriterion.AmountBetween(lo, hi)     => s"amount ${centsToStr(lo)}–${centsToStr(hi)}"
    case RuleCriterion.CurrencyIs(cur)           => s"currency = ${cur.code}"
  }

  private def opStr(op: TextMatchOp): String = op match {
    case TextMatchOp.Contains => "contains"
    case TextMatchOp.Equals   => "="
  }

  private def amountOpStr(op: AmountMatchOp): String = op match {
    case AmountMatchOp.Lt => "<"
    case AmountMatchOp.Eq => "="
    case AmountMatchOp.Gt => ">"
  }

  private def centsToStr(cents: Long): String = f"${cents / 100.0}%.2f"
  private def strToCents(s: String): Long     = scala.util.Try((s.trim.toDouble * 100).round).getOrElse(0L)

  def apply(
      openState: Var[Option[Seed]],
      cats: Signal[List[Category]],
      apiClient: ApiClient,
      onSaved: () => Unit,
      reloadCategories: () => Unit,
  ): HtmlElement =
    div(child.maybe <-- openState.signal.map(_.map(seed => modalContent(seed, openState, cats, apiClient, onSaved, reloadCategories))))

  private def modalContent(
      seed: Seed,
      openState: Var[Option[Seed]],
      cats: Signal[List[Category]],
      apiClient: ApiClient,
      onSaved: () => Unit,
      reloadCategories: () => Unit,
  ): HtmlElement = {
    // Stable per-criterion state (so text inputs keep focus across edits); rows can be appended via the "add condition" picker.
    final case class Row(key: Int, en: Var[Boolean], cr: Var[RuleCriterion])
    val rowsVar                        = Var(seed.criteria.zipWithIndex.map { case ((en, cr), i) => Row(i, Var(en), Var(cr)) })
    def addRow(c: RuleCriterion): Unit =
      rowsVar.update(rows => rows :+ Row(rows.map(_.key).maxOption.getOrElse(-1) + 1, Var(true), Var(c)))
    val nameVar                        = Var(seed.name)
    val catVar                         = Var(seed.categoryId)
    val errorVar                       = Var(Option.empty[String])
    val expanded                       = Var(false)

    val currentCriteria: Signal[List[RuleCriterion]] =
      rowsVar.signal
        .flatMapSwitch(rows => Signal.combineSeq(rows.map(r => r.en.signal.combineWith(r.cr.signal))))
        .map(_.collect { case (true, c) => c }.toList)

    val covers: Signal[Option[Boolean]] =
      currentCriteria.map(crit => seed.originTx.map(t => crit.nonEmpty && RuleMatcher.matches(crit, t)))

    def currentCriteriaNow: List[RuleCriterion] = rowsVar.now().collect { case r if r.en.now() => r.cr.now() }

    // Server-side match count: debounce criteria edits, then ask the backend (which holds all transactions) how many match. None = in flight.
    val preview: Signal[Option[RulePreviewResponse]] =
      currentCriteria.changes
        .debounce(300)
        .toSignal(currentCriteriaNow)
        .flatMapSwitch { crit =>
          EventStream
            .fromFuture(apiClient.rules.preview(crit).recover { case _ => RulePreviewResponse(0, 0, Nil) })
            .map(Option(_))
            .startWith(None)
        }

    def save(): Unit = {
      val criteria = currentCriteriaNow
      val name     = nameVar.now().trim
      (catVar.now(), criteria) match {
        case (None, _)         => errorVar.set(Some("Pick a category."))
        case (_, Nil)          => errorVar.set(Some("Enable at least one criterion."))
        case (Some(cat), crit) =>
          val fut = seed.editingId match {
            case Some(id) => apiClient.rules.update(id, UpdateRuleRequest(if name.isEmpty then "Rule" else name, cat, crit))
            case None     => apiClient.rules.create(CreateRuleRequest(if name.isEmpty then "Rule" else name, cat, crit))
          }
          fut.onComplete {
            case Success(_)  => openState.set(None); onSaved()
            case Failure(ex) => errorVar.set(Some(s"Failed to save rule: ${ex.getMessage}"))
          }
      }
    }

    div(
      div(
        cls       := "modal d-block",
        tabIndex  := -1,
        styleAttr := "background: rgba(0,0,0,0.5)",
        div(
          cls := "modal-dialog modal-lg modal-dialog-scrollable",
          div(
            cls := "modal-content",
            div(
              cls := "modal-header py-2",
              h5(cls     := "modal-title", if seed.editingId.isDefined then "Edit rule" else "New rule"),
              button(tpe := "button", cls := "btn-close", onClick --> { _ => openState.set(None) }),
            ),
            div(
              cls := "modal-body",
              child.maybe <-- errorVar.signal.map(_.map(e => div(cls := "alert alert-danger py-1 small", e))),
              div(
                cls   := "row g-2 mb-3",
                div(
                  cls := "col-md-6",
                  label(cls := "form-label small mb-1", "Rule name"),
                  input(cls := "form-control form-control-sm", controlled(value <-- nameVar.signal, onInput.mapToValue --> nameVar.writer)),
                ),
                div(
                  cls := "col-md-6",
                  label(cls := "form-label small mb-1", "Category"),
                  CategoryCombobox(cats, catVar.signal, opt => catVar.set(opt), apiClient, onCreated = reloadCategories),
                ),
              ),
              div(cls := "small text-muted mb-1", "Conditions (all must match):"),
              div(children <-- rowsVar.signal.split(_.key)((_, row, _) => criterionRow(row.en, row.cr))),
              addCriterionPicker(addRow),
              previewPanel(covers, preview, expanded, seed.originTx.isDefined),
            ),
            div(
              cls := "modal-footer py-2",
              button(tpe := "button", cls := "btn btn-sm btn-outline-secondary", "Cancel", onClick --> { _ => openState.set(None) }),
              button(tpe := "button", cls := "btn btn-sm btn-primary", "Save rule", onClick --> { _ => save() }),
            ),
          ),
        ),
      ),
    )
  }

  /** Blank criterion for each user-addable kind. Account is absent — its EB account uid only comes from seeding off a transaction. */
  private val addableKinds: List[(String, () => RuleCriterion)] = List(
    "Counterparty name"     -> (() => RuleCriterion.CounterpartyName(TextMatchOp.Contains, "")),
    "Remittance"            -> (() => RuleCriterion.Remittance(TextMatchOp.Contains, "")),
    "Counterparty IBAN"     -> (() => RuleCriterion.CounterpartyAccount("")),
    "Bank transaction code" -> (() => RuleCriterion.BankTransactionCode("")),
    "Direction"             -> (() => RuleCriterion.Direction(true)),
    "Amount"                -> (() => RuleCriterion.AmountCompare(AmountMatchOp.Eq, 0L)),
    "Amount between"        -> (() => RuleCriterion.AmountBetween(0L, 0L)),
    "Currency"              -> (() => RuleCriterion.CurrencyIs(Currency.PLN)),
  )

  private def addCriterionPicker(addRow: RuleCriterion => Unit): HtmlElement = {
    val selected = Var("")
    div(
      cls := "d-flex align-items-center gap-2 mt-1",
      select(
        cls := "form-select form-select-sm w-auto",
        value <-- selected.signal,
        onChange.mapToValue --> { v =>
          addableKinds.collectFirst { case (label, mk) if label == v => mk() }.foreach(addRow)
          selected.set("") // reset so the same kind can be added again
        },
        option(value := "", "+ Add condition…"),
        addableKinds.map { case (label, _) => option(value := label, label) },
      ),
    )
  }

  private def criterionRow(en: Var[Boolean], cr: Var[RuleCriterion]): HtmlElement =
    div(
      cls := "d-flex align-items-center gap-2 mb-1",
      input(cls := "form-check-input mt-0", tpe := "checkbox", checked <-- en.signal, onChange.mapToChecked --> en.writer),
      div(cls   := "flex-grow-1", criterionEditor(cr)),
    )

  private def criterionEditor(cr: Var[RuleCriterion]): HtmlElement = cr.now() match {
    case RuleCriterion.CounterpartyName(_, _)    => textEditor("Counterparty name", cr, isName = true)
    case RuleCriterion.Remittance(_, _)          => textEditor("Remittance", cr, isName = false)
    case RuleCriterion.CounterpartyAccount(iban) =>
      singleTextEditor("Counterparty IBAN", iban, v => cr.set(RuleCriterion.CounterpartyAccount(v)))
    case RuleCriterion.BankTransactionCode(code) =>
      singleTextEditor("Bank transaction code", code, v => cr.set(RuleCriterion.BankTransactionCode(v)))
    case RuleCriterion.Account(uid)              =>
      staticField("Account", uid.take(12) + "…")
    case RuleCriterion.Direction(outflow)        =>
      val dir = Var(outflow)
      labeled("Direction")(
        select(
          cls := "form-select form-select-sm w-auto",
          value <-- dir.signal.map(o => if o then "outflow" else "inflow"),
          onChange.mapToValue --> { v => dir.set(v == "outflow"); cr.set(RuleCriterion.Direction(v == "outflow")) },
          option(value := "outflow", "outflow (money out)"),
          option(value := "inflow", "inflow (money in)"),
        ),
      )
    case RuleCriterion.AmountCompare(op0, a)     =>
      val opVar        = Var(op0)
      val amt          = Var(centsToStr(a))
      def sync(): Unit = cr.set(RuleCriterion.AmountCompare(opVar.now(), strToCents(amt.now())))
      labeled("Amount")(
        select(
          cls := "form-select form-select-sm w-auto",
          value <-- opVar.signal.map(AmountMatchOp.asString),
          onChange.mapToValue --> { s => AmountMatchOp.fromString(s).foreach(opVar.set); sync() },
          option(value := "lt", "<"),
          option(value := "eq", "="),
          option(value := "gt", ">"),
        ),
        input(
          cls := "form-control form-control-sm w-auto",
          tpe := "number",
          controlled(value <-- amt.signal, onInput.mapToValue --> { s => amt.set(s); sync() }),
        ),
      )
    case RuleCriterion.AmountBetween(lo, hi)     =>
      val loV          = Var(centsToStr(lo)); val hiV = Var(centsToStr(hi))
      def sync(): Unit = cr.set(RuleCriterion.AmountBetween(strToCents(loV.now()), strToCents(hiV.now())))
      labeled("Amount between")(
        input(
          cls    := "form-control form-control-sm w-auto",
          tpe    := "number",
          controlled(value <-- loV.signal, onInput.mapToValue --> { s => loV.set(s); sync() }),
        ),
        span(cls := "small", "and"),
        input(
          cls    := "form-control form-control-sm w-auto",
          tpe    := "number",
          controlled(value <-- hiV.signal, onInput.mapToValue --> { s => hiV.set(s); sync() }),
        ),
      )
    case RuleCriterion.CurrencyIs(cur)           =>
      val curVar = Var(cur.code)
      labeled("Currency")(
        select(
          cls := "form-select form-select-sm w-auto",
          value <-- curVar.signal,
          onChange.mapToValue --> { c => curVar.set(c); cr.set(RuleCriterion.CurrencyIs(Currency(c))) },
          // keep an unknown stored code selectable rather than silently jumping to another currency
          if Currency.knownCurrencies.exists(_._1 == cur.code) then emptyNode else option(value := cur.code, cur.code),
          Currency.knownCurrencies.map { case (code, name) => option(value := code, s"$code — $name") },
        ),
      )
  }

  private def labeled(labelText: String)(controls: HtmlElement*): HtmlElement =
    div(cls := "d-flex align-items-center gap-2", span(cls := "small text-muted", labelText), controls)

  private def staticField(labelText: String, value: String): HtmlElement =
    div(cls := "d-flex align-items-center gap-2", span(cls := "small text-muted", labelText), span(cls := "badge text-bg-light", value))

  private def singleTextEditor(labelText: String, initial: String, set: String => Unit): HtmlElement = {
    val v = Var(initial)
    labeled(labelText)(
      input(cls := "form-control form-control-sm", controlled(value <-- v.signal, onInput.mapToValue --> { s => v.set(s); set(s) })),
    )
  }

  private def textEditor(labelText: String, cr: Var[RuleCriterion], isName: Boolean): HtmlElement = {
    val (op0, val0)  = cr.now() match {
      case RuleCriterion.CounterpartyName(o, v) => (o, v)
      case RuleCriterion.Remittance(o, v)       => (o, v)
      case _                                    => (TextMatchOp.Contains, "")
    }
    val opVar        = Var(op0)
    val valVar       = Var(val0)
    def sync(): Unit = {
      val op = opVar.now(); val vl = valVar.now()
      cr.set(if isName then RuleCriterion.CounterpartyName(op, vl) else RuleCriterion.Remittance(op, vl))
    }
    div(
      cls := "d-flex align-items-center gap-2",
      span(cls := "small text-muted", labelText),
      select(
        cls    := "form-select form-select-sm w-auto",
        value <-- opVar.signal.map { case TextMatchOp.Contains => "contains"; case TextMatchOp.Equals => "equals" },
        onChange.mapToValue --> { s => opVar.set(if s == "equals" then TextMatchOp.Equals else TextMatchOp.Contains); sync() },
        option(value := "contains", "contains"),
        option(value := "equals", "equals"),
      ),
      input(
        cls    := "form-control form-control-sm flex-grow-1",
        controlled(value <-- valVar.signal, onInput.mapToValue --> { s => valVar.set(s); sync() }),
      ),
    )
  }

  private def previewPanel(
      covers: Signal[Option[Boolean]],
      preview: Signal[Option[RulePreviewResponse]],
      expanded: Var[Boolean],
      hasOrigin: Boolean,
  ): HtmlElement =
    div(
      cls := "border rounded p-2 mt-3 bg-light",
      if hasOrigin then div(
        cls := "small mb-1",
        child.text <-- covers.map {
          case Some(true)  => "✓ Covers the originating transaction"
          case Some(false) => "✗ Does NOT cover the originating transaction — loosen the conditions"
          case None        => ""
        },
      )
      else emptyNode,
      div(
        cls := "d-flex justify-content-between align-items-center",
        span(
          cls := "small fw-semibold",
          child.text <-- preview.map {
            case Some(r) => s"Matches ${r.matched} of ${r.total} transactions"
            case None    => "Matching…"
          },
        ),
        button(
          tpe := "button",
          cls := "btn btn-sm btn-link p-0",
          child.text <-- expanded.signal.map(e => if e then "hide" else "show list"),
          onClick --> { _ => expanded.update(!_) },
        ),
      ),
      child.maybe <-- expanded.signal.combineWith(preview).map {
        case (true, Some(r)) =>
          Some(
            div(
              cls       := "mt-2",
              styleAttr := "max-height: 12rem; overflow-y: auto",
              if r.sample.isEmpty then span(cls := "small text-muted", "No matches.")
              else
                table(
                  cls                           := "table table-sm mb-0 small",
                  tbody(
                    r.sample.map { t =>
                      val muted = t.categorySource.contains(CategorySource.Manual)
                      tr(
                        cls := (if muted then "text-muted" else ""),
                        td(t.counterpartyName.orElse(t.remittance).getOrElse("—")),
                        td(cls := "text-end font-monospace", MoneyFormatter.formatSimple(t.amountCents, t.currency)),
                        td(
                          if muted then span(cls := "badge text-bg-light", "manual — kept")
                          else if t.internal then span(cls := "badge text-bg-light", "internal — skipped")
                          else emptyNode,
                        ),
                      )
                    },
                  ),
                ),
            ),
          )
        case _               => None
      },
    )
}
