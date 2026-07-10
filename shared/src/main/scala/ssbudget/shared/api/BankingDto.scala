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

/** A page of transactions matching the server-side filters, plus the total number that match (before the display cap). */
final case class TransactionListResponse(items: List[BankTransaction], total: Int) derives Codec.AsObject

/** Assign (or clear, when None) a transaction's spending category. */
final case class SetCategoryRequest(categoryId: Option[CategoryId]) derives Codec.AsObject

final case class CreateCategory(name: String, color: Option[String], monthlyBudget: Boolean = false) derives Codec.AsObject

final case class UpdateCategory(name: String, color: Option[String], monthlyBudget: Boolean = false) derives Codec.AsObject

/** Spending stats for a category, computed server-side from bank transactions (the browser no longer holds them). All amounts are converted to the
  * primary currency at the latest rates, so a category with mixed-currency transactions is counted in full.
  *
  *   - `avgMonthlyCents`: MEDIAN monthly outflow over the last 3 full calendar months (the budget when `category.monthlyBudget`); median, not mean,
  *     so a month that happens to hold two payments of a monthly bill doesn't inflate it.
  *   - `currentPeriodSpentCents`: outflow since the current budget period started.
  *   - `currency`: the primary currency (all category spend is converted to it).
  */
final case class CategorySummary(
    category: Category,
    avgMonthlyCents: Long,
    currentPeriodSpentCents: Long,
    currency: Currency,
) derives Codec.AsObject

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
