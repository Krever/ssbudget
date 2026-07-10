package ssbudget.frontend.components

import com.raquo.laminar.api.L.*
import ssbudget.frontend.services.ApiClient
import ssbudget.shared.api.CreateCategory
import ssbudget.shared.model.{Category, CategoryId}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

/** Typeahead category picker: type to filter, ↑/↓ to move, Enter to pick the highlighted row, click to select, optionally clear or create-on-the-fly.
  *
  * Selection is external — driven by `selectedId` and reported via `onSelect` — so the same component works both for deferred selection (bound to a
  * Var, e.g. the rule modal) and immediate selection (writing straight through to the server, e.g. a transaction row). The component owns only the
  * transient query/open/highlight UI state.
  */
object CategoryCombobox {

  /** One selectable row of the dropdown (a category pick, or the pseudo "clear"/"create" rows), with the action to run when chosen. */
  final private case class Entry(extraCls: String, label: String, run: () => Unit)

  def apply(
      cats: Signal[List[Category]],
      selectedId: Signal[Option[CategoryId]],
      onSelect: Option[CategoryId] => Unit,
      apiClient: ApiClient,
      onCreated: () => Unit = () => (),
      allowCreate: Boolean = true,
      allowClear: Boolean = false,
      placeholderText: String = "Search or create a category…",
  ): HtmlElement = {
    val query        = Var("")
    val open         = Var(false)
    val highlight    = Var(0)  // index into the current entries list
    val entriesVar   = Var(List.empty[Entry])
    val selectedName = Var("") // mirrors the selected category's name, so blur/Escape can revert unfinished typing

    def pick(c: Category): Unit = { onSelect(Some(c.id)); query.set(c.name); open.set(false) }
    def clear(): Unit           = { onSelect(None); query.set(""); open.set(false) }

    def create(rawName: String): Unit = {
      val name = rawName.trim
      if name.nonEmpty then apiClient.categories.create(CreateCategory(name, None)).onComplete {
        case Success(created) => onSelect(Some(created.id)); query.set(created.name); open.set(false); onCreated()
        case Failure(_)       => ()
      }
    }

    // Ordered dropdown rows: [clear] ++ filtered categories ++ [create]. Same order used for rendering and for Enter/↑↓.
    val entries: Signal[List[Entry]] =
      cats.combineWith(query.signal).map { case (categories, q) =>
        val needle  = q.trim
        val matches = if needle.isEmpty then categories else categories.filter(_.name.toLowerCase.contains(needle.toLowerCase))
        val clearE  = if allowClear then List(Entry("text-muted", "— (clear)", () => clear())) else Nil
        val createE =
          if allowCreate && needle.nonEmpty && !categories.exists(_.name.equalsIgnoreCase(needle)) then List(
            Entry("text-primary", s"＋ Create “$needle”", () => create(needle)),
          )
          else Nil
        clearE ++ matches.map(c => Entry("", c.name, () => pick(c))) ++ createE
      }

    def runHighlighted(): Unit = {
      val es = entriesVar.now()
      val h  = highlight.now()
      if open.now() && h >= 0 && h < es.size then es(h).run()
    }

    div(
      cls := "position-relative",
      // Keep the entries mirror in sync (so key handlers can read them synchronously) and clamp the highlight.
      entries --> Observer[List[Entry]] { es => entriesVar.set(es); if highlight.now() >= es.size then highlight.set(0) },
      // Keep the input text synced to the current selection: seeds it, and reverts abandoned typing on blur/Escape.
      cats.combineWith(selectedId) --> Observer[(List[Category], Option[CategoryId])] { case (categories, sel) =>
        val name = sel.flatMap(id => categories.find(_.id == id)).map(_.name).getOrElse("")
        selectedName.set(name)
        if !open.now() then query.set(name)
      },
      input(
        cls         := "form-control form-control-sm",
        placeholder := placeholderText,
        controlled(value <-- query.signal, onInput.mapToValue --> { v => query.set(v); open.set(true); highlight.set(0) }),
        onFocus --> { _ => open.set(true) },
        onBlur --> { _ => open.set(false); query.set(selectedName.now()) },
        onKeyDown --> { ev =>
          ev.key match {
            case "ArrowDown" =>
              ev.preventDefault()
              if !open.now() then { open.set(true); highlight.set(0) }
              else highlight.update(h => math.min(entriesVar.now().size - 1, h + 1))
            case "ArrowUp"   => ev.preventDefault(); highlight.update(h => math.max(0, h - 1))
            case "Enter"     => if open.now() && entriesVar.now().nonEmpty then { ev.preventDefault(); runHighlighted() }
            case "Escape"    => open.set(false); query.set(selectedName.now())
            case _           => ()
          }
        },
      ),
      child.maybe <-- open.signal.combineWith(entriesVar.signal).combineWith(highlight.signal).map { case (isOpen, es, hi) =>
        Option.when(isOpen) {
          val body =
            if es.isEmpty then List(div(cls := "list-group-item small text-muted", "No categories"))
            else
              es.zipWithIndex.map { case (e, i) =>
                button(
                  tpe := "button",
                  cls := s"list-group-item list-group-item-action py-1 small ${e.extraCls} ${if i == hi then "active" else ""}",
                  onMouseDown.preventDefault --> { _ => e.run() }, // mousedown fires before the input's blur, so the click isn't lost
                  e.label,
                )
              }
          div(cls := "list-group position-absolute w-100 shadow-sm", styleAttr := "z-index: 1080; max-height: 12rem; overflow-y: auto", body)
        }
      },
    )
  }
}
