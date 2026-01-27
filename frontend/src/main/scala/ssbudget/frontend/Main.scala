package ssbudget.frontend

import com.raquo.laminar.api.L.{*, given}
import org.scalajs.dom
import ssbudget.frontend.components.Layout

object Main {

  def main(args: Array[String]): Unit = {
    val container = dom.document.getElementById("app")
    render(container, Layout())
  }
}
