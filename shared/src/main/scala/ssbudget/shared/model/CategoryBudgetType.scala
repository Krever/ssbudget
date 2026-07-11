package ssbudget.shared.model

import io.circe.Codec
import ssbudget.shared.json.EnumCodec

/** How a category's monthly budget predicts the money still needed before the next paycheck. Set per category (None = not a budget):
  *   - [[Steady]]: time-based (groceries, restaurants, fuel). Reserve the remaining-time share of the budget; overspending never zeroes it out ("you
  *     still have to eat").
  *   - [[Bill]]: one payment per period (kindergarten, rent). Reserve the full expected amount until a payment lands, then 0 (regardless of the exact
  *     amount).
  *   - [[Subscription]]: fixed pool (subscriptions). Reserve `budget − spent`; not time-sensitive — pay them all early and nothing more is reserved.
  */
enum CategoryBudgetType {
  case Steady, Bill, Subscription
}

object CategoryBudgetType {

  def asString(t: CategoryBudgetType): String = t match {
    case Steady       => "steady"
    case Bill         => "bill"
    case Subscription => "subscription"
  }

  def fromString(s: String): Either[String, CategoryBudgetType] = s match {
    case "steady"       => Right(Steady)
    case "bill"         => Right(Bill)
    case "subscription" => Right(Subscription)
    case other          => Left(s"Unknown category budget type: $other")
  }

  /** Money still expected to be spent before the next paycheck, given the budget type. `budgetCents` = the monthly budget (auto average), `spentCents`
    * = spend so far this period, `elapsed` = fraction of the period elapsed (0..1). See the type docs for the rationale of each formula.
    */
  def remaining(t: CategoryBudgetType, budgetCents: Long, spentCents: Long, elapsed: Double): Long = {
    val e = math.max(0.0, math.min(1.0, elapsed))
    t match {
      case Steady       => math.max(0L, ((1.0 - e) * budgetCents).toLong)           // remaining-time share of the budget; independent of overspend
      case Bill         => if spentCents > 0 then 0L else math.max(0L, budgetCents) // paid on any spend this period
      case Subscription => math.max(0L, budgetCents - spentCents)                   // fixed pool: pay it down to zero
    }
  }

  given Codec[CategoryBudgetType] = EnumCodec(values, asString, "category budget type")
}
