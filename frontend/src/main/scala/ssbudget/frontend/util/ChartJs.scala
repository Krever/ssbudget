package ssbudget.frontend.util

import org.scalajs.dom
import scala.scalajs.js
import scala.scalajs.js.annotation.*

/** Minimal Scala.js facade over [Chart.js](https://www.chartjs.org/) v4.
  *
  * `chart.js/auto` default-exports the `Chart` class with every controller/element/scale pre-registered, so no manual `Chart.register(...)` is
  * needed. Vite bundles the npm import (the app is linked as an ES module); `npm install` in `frontend/` must have pulled in `chart.js`.
  */
@js.native
@JSImport("chart.js/auto", JSImport.Default)
class Chart(ctx: dom.HTMLCanvasElement, config: js.Any) extends js.Object {

  /** Tear down the chart and detach its listeners — call on unmount to avoid leaks. */
  def destroy(): Unit = js.native

  /** Re-render after mutating `data`/`options` in place. */
  def update(): Unit = js.native
}
