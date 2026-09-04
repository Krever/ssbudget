package ssbudget.frontend.pages

import com.raquo.laminar.api.L.*
import ssbudget.frontend.services.{AnalyticsState, ApiClient}
import ssbudget.shared.api.AnalyticsConfig

import scala.concurrent.ExecutionContext.Implicits.global

/** Analytics, served by the bundled Metabase instance.
  *
  * The dashboard is a static embed: the backend signs a short-lived JWT naming one dashboard, so the iframe needs no login of its own. The URL is
  * therefore minted per visit rather than kept around.
  *
  * "Open in Metabase" is a full-page link, not an iframe — that is where free-form exploration happens, and the dashboard shown here is the user's to
  * edit there. It is a plain anchor rather than a router link because the target is proxied Metabase, not a page of this SPA.
  */
object AnalyticsPage {

  def apply(apiClient: ApiClient): HtmlElement = {
    div(
      cls := "container-fluid px-3 pt-3",
      // Re-fetch on mount: the embed token is short-lived, and analytics may have finished provisioning since startup.
      onMountCallback { _ => AnalyticsState.load(apiClient) },
      child <-- AnalyticsState.config.map {
        case None                      => loading
        case Some(cfg) if !cfg.enabled => disabled
        case Some(cfg)                 =>
          cfg.dashboardUrl match {
            case Some(url) => dashboard(url, cfg.exploreUrl)
            case None      => notReady(cfg.exploreUrl)
          }
      },
    )
  }

  private def dashboard(embedUrl: String, exploreUrl: Option[String]): HtmlElement =
    div(
      div(
        cls       := "d-flex justify-content-between align-items-center mb-2",
        h5(cls := "mb-0", "Analytics"),
        exploreLink(exploreUrl),
      ),
      iframe(
        src       := embedUrl,
        cls       := "w-100 border rounded",
        styleAttr := "height: calc(100vh - 140px);",
      ),
    )

  private def exploreLink(exploreUrl: Option[String]): Modifier[HtmlElement] =
    exploreUrl.map(url =>
      a(
        cls    := "btn btn-outline-secondary btn-sm",
        href   := url,
        target := "_blank",
        rel    := "noopener",
        "Open in Metabase",
      ),
    )

  private def loading: HtmlElement =
    div(
      cls := "d-flex justify-content-center py-5",
      div(cls := "spinner-border text-primary", role := "status"),
    )

  /** Analytics is genuinely optional, so say so plainly rather than showing a broken frame. */
  private def disabled: HtmlElement =
    div(
      cls := "alert alert-secondary",
      strong("Analytics is not enabled for this deployment."),
      div(
        cls := "small mt-1",
        "Start the bundled Metabase and set ",
        code("SSBUDGET_METABASE_URL"),
        ", ",
        code("SSBUDGET_METABASE_PASSWORD"),
        " and ",
        code("SSBUDGET_METABASE_EMBED_SECRET"),
        ". See ",
        code("docs/metabase-analytics.md"),
        ".",
      ),
    )

  /** Metabase is configured but the dashboard hasn't been seeded yet — normally just a slow first boot. */
  private def notReady(exploreUrl: Option[String]): HtmlElement =
    div(
      cls := "alert alert-info d-flex justify-content-between align-items-center",
      div(
        strong("Setting up analytics…"),
        div(cls := "small mt-1", "Metabase is still starting or being provisioned. Reload in a moment."),
      ),
      exploreLink(exploreUrl),
    )
}
