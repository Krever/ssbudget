package ssbudget.shared.model

import io.circe.Codec

/** One month of a category's history: its "YYYY-MM" bucket, the net amount that moved — SIGNED like every other figure in the app, negative when the
  * money came in — and whether the category's lookback window counts it.
  *
  * `included` is decided where the statistic is computed rather than by whoever draws it, so the shading on the chart and the number above it can
  * never come from two different readings of the window (or two different clocks).
  */
final case class MonthlySpend(month: String, cents: Long, included: Boolean) derives Codec.AsObject
