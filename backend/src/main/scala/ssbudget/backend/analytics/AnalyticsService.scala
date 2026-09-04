package ssbudget.backend.analytics

import cats.effect.IO
import ssbudget.backend.db.repository.AnalyticsStateRepository
import ssbudget.shared.api.AnalyticsConfig

/** What the Analytics page needs to render: whether analytics exists at all, a freshly signed URL for the embedded dashboard, and where to go for
  * free-form exploration.
  *
  * The embed URL is minted per request because its JWT is short-lived — the URL is the credential for that one dashboard, so it should not be
  * long-lived or bookmarkable.
  */
class AnalyticsService(config: Option[MetabaseConfig], state: AnalyticsStateRepository) {

  def status: IO[AnalyticsConfig] =
    config match {
      case None      => IO.pure(AnalyticsConfig(enabled = false, None, None))
      case Some(cfg) =>
        for {
          dashboardId <- state.canonicalDashboardId
          now         <- IO.realTimeInstant
        } yield AnalyticsConfig(
          enabled = true,
          dashboardUrl = dashboardId.map { id =>
            val token = EmbedToken.forDashboard(cfg.embeddingSecret, id, now)
            s"${cfg.path}/embed/dashboard/$token#bordered=false&titled=false"
          },
          exploreUrl = Some(s"${cfg.path}/"),
        )
    }
}
