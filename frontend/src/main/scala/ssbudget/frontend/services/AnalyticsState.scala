package ssbudget.frontend.services

import com.raquo.laminar.api.L.*
import ssbudget.shared.api.AnalyticsConfig

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

/** Whether this deployment ships Metabase analytics, fetched once at startup.
  *
  * Analytics is optional, so the nav entry and page hide themselves entirely when the backend reports it disabled — a deployment without Metabase
  * looks exactly as it did before the integration existed.
  */
object AnalyticsState {

  private val state: Var[Option[AnalyticsConfig]] = Var(None)

  val config: Signal[Option[AnalyticsConfig]] = state.signal

  val enabled: Signal[Boolean] = state.signal.map(_.exists(_.enabled))

  /** Fetches the config. Worth re-running on each visit to the Analytics page: the embed URL it carries is a short-lived signed token. */
  def load(apiClient: ApiClient)(implicit ec: ExecutionContext): Unit =
    apiClient.analytics.config().onComplete {
      case Success(cfg) => state.set(Some(cfg))
      // A failed call is not the same as "not deployed", so leave any earlier answer in place.
      case Failure(_)   => if state.now().isEmpty then state.set(Some(AnalyticsConfig(enabled = false, None, None)))
    }
}
