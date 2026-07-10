package ssbudget.backend.banking

import cats.effect.IO
import org.slf4j.LoggerFactory
import ssbudget.backend.db.Repositories
import ssbudget.shared.model.*
import ssbudget.shared.rules.RuleMatcher

/** Applies user-defined categorization rules to all transactions. Idempotent, full re-evaluation:
  *
  *   - Manual categories (category_source = 'manual') are never touched.
  *   - Every other row is resolved from scratch against the current rules — the first matching rule (by ascending priority) sets the category with
  *     source 'rule'; a row that no longer matches any rule (or is internal) reverts to uncategorized. So editing/reordering/deleting rules always
  *     leaves rule-assigned categories consistent with the current rule set.
  *
  * Run after any rule change and after every import.
  */
class RuleEngineService(repos: Repositories) {

  private val log = LoggerFactory.getLogger(classOf[RuleEngineService])

  def applyRules(): IO[Int] =
    for {
      rules  <- repos.classificationRules.findAll // ascending priority
      txs    <- repos.bankTransactions.list(None, None, None)
      updates = txs.flatMap { tx =>
                  if tx.categorySource.contains(CategorySource.Manual) then None // manual always wins
                  else {
                    val desired: (Option[CategoryId], Option[CategorySource]) =
                      RuleMatcher.firstMatch(rules, tx) match { // returns None for internal transfers
                        case Some(rule) => (Some(rule.categoryId), Some(CategorySource.Rule))
                        case None       => (None, None)
                      }
                    if desired == ((tx.categoryId, tx.categorySource)) then None
                    else Some((tx.id, desired._1, desired._2))
                  }
                }
      _      <- repos.bankTransactions.applyCategoryUpdates(updates)
      _      <- IO(log.info(s"[rules] applied ${rules.size} rule(s): ${updates.size} transaction(s) re-categorized"))
    } yield updates.size
}
