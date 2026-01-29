package ssbudget.frontend.components

import com.raquo.laminar.api.L.*
import org.scalajs.dom

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

object Loading {

  /** Bootstrap spinner (small, inline) */
  def spinner: HtmlElement =
    span(cls := "spinner-border spinner-border-sm", role := "status")

  /** Render content based on loading state */
  def render[T](state: Signal[LoadingState[T]])(content: T => HtmlElement): HtmlElement =
    div(
      child <-- state.map {
        case LoadingState.Loading      => spinner
        case LoadingState.Loaded(data) => content(data)
        case LoadingState.Error(msg)   => span(cls := "text-danger", msg)
      },
    )

  /** Button that shows spinner while action is in progress */
  def actionButton(
      label: String,
      action: () => Future[Unit],
      btnClass: String = "btn btn-primary btn-sm",
  ): HtmlElement = {
    val loading = Var(false)
    button(
      tpe := "button",
      cls := btnClass,
      disabled <-- loading.signal,
      child <-- loading.signal.map {
        case true  => spinner
        case false => span(label)
      },
      onClick --> { _ =>
        loading.set(true)
        action().onComplete { result =>
          loading.set(false)
          result match {
            case Failure(ex) =>
              dom.console.error(s"Action failed: ${ex.getMessage}")
            case Success(_)  => // success, nothing to do
          }
        }
      },
    )
  }

  /** Action group that provides a button and onKeyDown handler sharing the same loading state. Use this when you want Enter key on inputs to trigger
    * the same action as clicking the button.
    */
  class ActionGroup(
      label: String,
      action: () => Future[Unit],
      btnClass: String = "btn btn-primary btn-sm",
  ) {
    private val loading = Var(false)

    private def executeAction(): Unit = {
      if !loading.now() then {
        loading.set(true)
        action().onComplete { result =>
          loading.set(false)
          result match {
            case Failure(ex) =>
              dom.console.error(s"Action failed: ${ex.getMessage}")
            case Success(_)  => // success
          }
        }
      }
    }

    /** The button element */
    val btn: HtmlElement = button(
      tpe := "button",
      cls := btnClass,
      disabled <-- loading.signal,
      child <-- loading.signal.map {
        case true  => spinner
        case false => span(label)
      },
      onClick --> { _ => executeAction() },
    )

    /** onKeyDown modifier that triggers action on Enter key */
    val onEnter: Modifier[HtmlElement] = onKeyDown --> { ev =>
      if ev.key == "Enter" then executeAction()
    }
  }

  /** Create an action group for shared button/Enter key handling */
  def actionGroup(
      label: String,
      action: () => Future[Unit],
      btnClass: String = "btn btn-primary btn-sm",
  ): ActionGroup = new ActionGroup(label, action, btnClass)

  /** Button with confirm dialog before action */
  def confirmActionButton(
      label: String,
      confirmMessage: String,
      action: () => Future[Unit],
      btnClass: String = "btn btn-danger btn-sm",
  ): HtmlElement = {
    val loading = Var(false)
    button(
      tpe := "button",
      cls := btnClass,
      disabled <-- loading.signal,
      child <-- loading.signal.map {
        case true  => spinner
        case false => span(label)
      },
      onClick --> { _ =>
        if dom.window.confirm(confirmMessage) then {
          loading.set(true)
          action().onComplete { result =>
            loading.set(false)
            result match {
              case Failure(ex) =>
                dom.console.error(s"Action failed: ${ex.getMessage}")
              case Success(_)  => // success
            }
          }
        }
      },
    )
  }
}
