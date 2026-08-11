package ssbudget.shared.api

import io.circe.Codec
import ssbudget.shared.model.*

/** A bank (ASPSP) that can be connected via Enable Banking. */
final case class Aspsp(name: String, country: String) derives Codec.AsObject

final case class ConnectBankRequest(aspspName: String, aspspCountry: String) derives Codec.AsObject

/** URL the browser must be redirected to so the user can authorize at their bank. */
final case class ConnectBankResponse(redirectUrl: String) derives Codec.AsObject

final case class BankCallbackRequest(code: String, state: String) derives Codec.AsObject

/** A connection plus its authorized bank accounts. */
final case class BankConnectionView(
    connection: BankConnection,
    accounts: List[BankAccountLink],
) derives Codec.AsObject

/** Point a bank account link at its [[BankLinkTarget]] (or `Unlinked` to detach). */
final case class LinkAccountRequest(target: BankLinkTarget) derives Codec.AsObject

/** Link a card group's remaining-limit mirror to an app account (`None` to unlink). */
final case class LinkCardGroupRequest(accountId: Option[AccountId]) derives Codec.AsObject

/** Create a shared-limit credit-card group (initially unlinked; link it to an app account afterwards to feed the budget). */
final case class CreateCardGroup(name: String, limitCents: Long, currency: Currency) derives Codec.AsObject

/** Import transactions for a connection. `monthsBack = None` = incremental (only newer than what we have); `Some(n)` = backfill the last n months. */
final case class ImportTransactionsRequest(monthsBack: Option[Int]) derives Codec.AsObject

/** Per-account outcome of an import run. */
final case class AccountImportResult(ebAccountUid: String, imported: Int, skipped: Int) derives Codec.AsObject

final case class ImportResult(accounts: List[AccountImportResult]) derives Codec.AsObject {
  def totalImported: Int = accounts.map(_.imported).sum
  def totalSkipped: Int  = accounts.map(_.skipped).sum
}

/** A page of transactions matching the server-side filters, plus the total number that match (before the display cap) and the net signed sum per
  * currency over the FULL match (`sums`) so the UI can show a reliable total even when `items` is capped.
  */
final case class TransactionListResponse(items: List[BankTransaction], total: Int, sums: List[Money]) derives Codec.AsObject

/** Values the transaction-list `month` filter accepts beyond a `YYYY-MM` bucket. These two are resolved server-side to the same date windows that
  * back `CategorySummary.currentPeriodSpentCents` / `lastPeriodSpentCents`, so drilling into a category budget lists exactly the transactions its
  * figure was computed from. Shared because the backend, the month dropdown and the drill-down links all have to agree on the wire value.
  */
object MonthFilter {
  val CurrentPeriod  = "current-period"
  val PreviousPeriod = "previous-period"

  /** No month filter at all. */
  val All = "all"
}

/** Values the transaction-list `category` filter accepts besides a category id. Shared for the same reason as [[MonthFilter]]: the query string, the
  * dropdown and the SQL that interprets them have to agree.
  */
object CategoryFilter {
  val All           = "all"
  val Uncategorized = "uncategorized" // no category AND not internal — the triage backlog
}

/** Assign (or clear, when None) a transaction's spending category. */
final case class SetCategoryRequest(categoryId: Option[CategoryId]) derives Codec.AsObject

/** Set (or clear, when None/blank) a transaction's free-text note. */
final case class SetNoteRequest(note: Option[String]) derives Codec.AsObject

final case class CreateCategory(name: String, color: Option[String], budgetType: Option[CategoryBudgetType] = None) derives Codec.AsObject

final case class UpdateCategory(name: String, color: Option[String], budgetType: Option[CategoryBudgetType] = None) derives Codec.AsObject

/** Spending stats for a category, computed server-side from bank transactions (the browser no longer holds them). All amounts are converted to the
  * primary currency at the latest rates, so a category with mixed-currency transactions is counted in full.
  *
  *   - `avgMonthlyCents`: MEAN monthly NET spend over the category's active span — total completed-month spend divided by the number of months from
  *     its first to its last month-with-spend (the budget when `category.budgetType` is set). The in-progress current month is excluded and empty
  *     leading/trailing months don't count, so a recently-started or dormant category isn't diluted by zeros.
  *   - `currentPeriodSpentCents`: net spend since the current budget period started.
  *   - `lastPeriodSpentCents`: net spend over the previous (most recent closed) period; 0 if there is none.
  *   - `currency`: the primary currency (all category spend is converted to it).
  *   - `overrideRemainingCents`: manual remaining-amount override for the CURRENT period, when the user set one (see [[remainingCents]]).
  *
  * Spend is NET (outflows minus inflows), so pure-inflow categories (salary, refunds) show a negative figure instead of 0, and refunds reduce a
  * category's spend.
  */
final case class CategorySummary(
    category: Category,
    avgMonthlyCents: Long,
    currentPeriodSpentCents: Long,
    lastPeriodSpentCents: Long,
    currency: Currency,
    overrideRemainingCents: Option[Long] = None,
) derives Codec.AsObject {

  /** Which way this category's money flows: `1` when it is spent, `-1` when it arrives (its net spend, and so its budget, is negative). This is the
    * ONE rule for direction in the app — everything else multiplies by it rather than re-deriving it from a sign somewhere.
    */
  def direction: Long = if avgMonthlyCents < 0 || (avgMonthlyCents == 0 && currentPeriodSpentCents < 0) then -1L else 1L

  /** Whether this category's money flows IN. Drives wording and colour. */
  def isIncome: Boolean = direction < 0

  /** The budget, as a magnitude in the category's own direction: what is expected to be spent, or to arrive. */
  def expectedMagnitude: Long = math.abs(avgMonthlyCents)

  /** What has already moved this period in the category's own direction — spent for an expense, received for an income. Negative if it moved the
    * other way (a refund on an expense category, say), which the budget formulas treat as no progress rather than as progress backwards.
    */
  def movedMagnitude: Long = currentPeriodSpentCents * direction

  /** What is still expected to move this period, as a magnitude in the category's own direction. A manual override wins over the budget-type formula
    * — the user knows something the transactions don't yet show (e.g. the bill was already paid) — and is stored signed, so it converts the same way.
    */
  def remainingMagnitude(elapsed: Double): Long =
    overrideRemainingCents.map(_ * direction).getOrElse {
      CategoryBudgetType.remaining(category.budgetType.getOrElse(CategoryBudgetType.Steady), expectedMagnitude, movedMagnitude, elapsed)
    }

  /** Money still expected to move in this category before the next paycheck, SIGNED: positive is still to be spent, negative is still to arrive. This
    * is the form the free-money sums add up; anything rendering a single category wants [[remainingMagnitude]] instead. An override of 0 means the
    * budget is covered, which is what makes an overridden-to-zero budget behave exactly like a paid one wherever this is consulted.
    */
  def remainingCents(elapsed: Double): Long = remainingMagnitude(elapsed) * direction
}

/** Manually set a category budget's remaining amount for the current period (cents in the primary currency; 0 = already covered). */
final case class SetCategoryOverrideRequest(remainingCents: Long) derives Codec.AsObject

/** Create a categorization rule. Priority is assigned server-side (appended last); criteria must be non-empty. */
final case class CreateRuleRequest(name: String, categoryId: CategoryId, criteria: List[RuleCriterion]) derives Codec.AsObject

/** Update a rule's name, target category, and criteria (priority is changed only via reorder). */
final case class UpdateRuleRequest(name: String, categoryId: CategoryId, criteria: List[RuleCriterion]) derives Codec.AsObject

/** New rule order; the server rewrites priorities to the index of each id in this list. */
final case class ReorderRulesRequest(orderedIds: List[ClassificationRuleId]) derives Codec.AsObject

/** Outcome of a rule (re-)application: how many transactions changed category. */
final case class ApplyRulesResult(updated: Int) derives Codec.AsObject

/** A rule in portable form: the target category is carried by NAME (not id) so an export can be imported into another database. Priority is implied
  * by position in [[RulesExport.rules]].
  */
final case class RuleExport(name: String, categoryName: String, criteria: List[RuleCriterion]) derives Codec.AsObject

/** A portable bundle of classification rules. `version` guards the format for future changes. */
final case class RulesExport(version: Int, rules: List[RuleExport]) derives Codec.AsObject

/** Import a rules bundle. `replace = true` clears existing rules first; otherwise the imported rules are appended. Missing categories (matched by
  * name, case-insensitively) are created.
  */
final case class ImportRulesRequest(replace: Boolean, bundle: RulesExport) derives Codec.AsObject

/** Outcome of an import: how many rules were created and how many new categories were needed. */
final case class ImportRulesResult(rulesImported: Int, categoriesCreated: Int) derives Codec.AsObject

/** Live rule-preview request: how many stored transactions would these criteria match? Criteria are AND-ed (empty = matches nothing). */
final case class RulePreviewRequest(criteria: List[RuleCriterion]) derives Codec.AsObject

/** Rule-preview result: `matched` of `total` stored transactions match; `sample` is a capped list for display (with manual/internal flags intact). */
final case class RulePreviewResponse(matched: Int, total: Int, sample: List[BankTransaction]) derives Codec.AsObject
