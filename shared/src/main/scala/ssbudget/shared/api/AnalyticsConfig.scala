package ssbudget.shared.api

import io.circe.Codec

/** What the frontend needs to know about the optional Metabase instance.
  *
  *   - [[enabled]] is false when the deployment ships without analytics; the page and nav entry hide themselves.
  *   - [[dashboardUrl]] is a signed static-embed URL for the canonical dashboard, valid for minutes — mint it per page load rather than caching it.
  *     It is absent while analytics is enabled but the dashboard has not been seeded (e.g. Metabase was still booting).
  *   - [[exploreUrl]] opens the full Metabase UI, proxied under the app's own origin.
  */
final case class AnalyticsConfig(
    enabled: Boolean,
    dashboardUrl: Option[String],
    exploreUrl: Option[String],
) derives Codec.AsObject
