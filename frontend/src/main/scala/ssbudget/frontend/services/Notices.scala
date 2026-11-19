package ssbudget.frontend.services

import com.raquo.laminar.api.L.*
import ssbudget.shared.api.DataWarning

/** What the user must be told on every page: a write that failed, and faults in the data that make a figure wrong.
  *
  * A failed write was not shown before: the page kept the typed value as if it was stored. [[ApiClient]] reports every failed write here, so no page
  * has to remember to do it. `components.NoticeStrip` shows the notices above the page.
  */
object Notices {

  final case class Failure(id: Int, text: String)

  private val keep   = 3
  private var nextId = 0

  val failures: Var[List[Failure]]     = Var(Nil)
  val warnings: Var[List[DataWarning]] = Var(Nil)

  /** Shows a failure until the user dismisses it. The same text is not shown two times, and only the newest few stay. */
  def failed(text: String): Unit =
    if !failures.now().exists(_.text == text) then {
      nextId += 1
      failures.update(list => (list :+ Failure(nextId, text)).takeRight(keep))
    }

  def dismiss(id: Int): Unit = failures.update(_.filterNot(_.id == id))

  def clear(): Unit = failures.set(Nil)
}
