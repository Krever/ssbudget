package ssbudget.shared.api

import io.circe.Codec
import ssbudget.shared.model.*

/** Everything the Analytics page needs, computed server-side in one round trip. All amounts are in [[currency]] (the primary currency);
  * mixed-currency spend is converted at the latest rates before aggregation.
  *
  *   - [[months]] is the chronological x-axis (oldest → newest), each a `YYYY-MM` bucket.
  *   - [[series]] holds one entry per category that had any spend in the window, sorted by window total (largest first). Each series' `monthly` list
  *     is parallel to [[months]] (zero-filled).
  *   - [[monthlyTotals]] is the summed spend per month (parallel to [[months]]).
  */
final case class AnalyticsResponse(
    currency: Currency,
    months: List[String],
    series: List[CategorySpendSeries],
    monthlyTotals: List[Long],
    categorization: CategorizationStats,
    topUncategorized: List[UncategorizedCounterparty],
) derives Codec.AsObject

/** A category's spend across the analytics window. `monthly` is parallel to [[AnalyticsResponse.months]]; `total` is their sum. */
final case class CategorySpendSeries(
    category: Category,
    monthly: List[Long],
    total: Long,
) derives Codec.AsObject

/** Import/categorization health across all stored transactions.
  *
  *   - [[total]]: all transactions. [[internal]]: own-account transfers (excluded from spend).
  *   - [[categorized]]/[[uncategorized]]: non-internal transactions with / without a category.
  *   - [[manual]]/[[rule]]: how the categorized ones got their category.
  *   - [[uncategorizedOutflowCents]]: total outflow (primary currency) still lacking a category — the size of the triage backlog.
  */
final case class CategorizationStats(
    total: Int,
    internal: Int,
    categorized: Int,
    uncategorized: Int,
    manual: Int,
    rule: Int,
    uncategorizedOutflowCents: Long,
) derives Codec.AsObject

/** A counterparty with uncategorized outflow — the actionable list for creating new rules. `outflowCents` is in the primary currency. */
final case class UncategorizedCounterparty(
    name: String,
    count: Int,
    outflowCents: Long,
) derives Codec.AsObject
