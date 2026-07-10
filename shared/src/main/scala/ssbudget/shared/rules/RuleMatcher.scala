package ssbudget.shared.rules

import ssbudget.shared.model.*

/** Pure matching logic shared by the backend (applying rules) and the frontend (live preview in the rule modal) — one source of truth. */
object RuleMatcher {

  private def normText(s: String): String = s.trim.replaceAll("\\s+", " ").toUpperCase
  private def normIban(s: String): String = s.replaceAll("\\s", "").toUpperCase

  private def textMatch(op: TextMatchOp, field: Option[String], value: String): Boolean =
    field.exists { f =>
      val (a, b) = (normText(f), normText(value))
      op match {
        case TextMatchOp.Contains => a.contains(b)
        case TextMatchOp.Equals   => a == b
      }
    }

  def matchesOne(c: RuleCriterion, tx: BankTransaction): Boolean = c match {
    case RuleCriterion.CounterpartyName(op, v)   => textMatch(op, tx.counterpartyName, v)
    case RuleCriterion.Remittance(op, v)         => textMatch(op, tx.remittance, v)
    case RuleCriterion.CounterpartyAccount(iban) => tx.counterpartyAccount.exists(a => normIban(a) == normIban(iban))
    case RuleCriterion.BankTransactionCode(code) => tx.bankTransactionCode.contains(code)
    case RuleCriterion.Account(uid)              => tx.ebAccountUid == uid
    case RuleCriterion.Direction(outflow)        => tx.isOutflow == outflow
    case RuleCriterion.AmountCompare(op, a)      =>
      val abs = math.abs(tx.amountCents)
      op match {
        case AmountMatchOp.Lt => abs < a
        case AmountMatchOp.Eq => abs == a
        case AmountMatchOp.Gt => abs > a
      }
    case RuleCriterion.AmountBetween(lo, hi)     => val a = math.abs(tx.amountCents); a >= lo && a <= hi
    case RuleCriterion.CurrencyIs(cur)           => tx.currency == cur
  }

  /** All criteria must match (AND). Empty criteria never match — a rule must have at least one condition. */
  def matches(criteria: List[RuleCriterion], tx: BankTransaction): Boolean =
    criteria.nonEmpty && criteria.forall(matchesOne(_, tx))

  /** First rule (by the given ascending-priority order) whose criteria all match. Internal transfers are never categorized. */
  def firstMatch(rulesByPriority: List[ClassificationRule], tx: BankTransaction): Option[ClassificationRule] =
    if tx.internal then None else rulesByPriority.find(r => matches(r.criteria, tx))

  /** Criteria offered when creating a rule from a transaction, in modal display order (the user trims what they don't want). */
  def candidateCriteria(tx: BankTransaction): List[RuleCriterion] = List(
    tx.counterpartyName.map(v => RuleCriterion.CounterpartyName(TextMatchOp.Contains, v)),
    tx.remittance.map(v => RuleCriterion.Remittance(TextMatchOp.Contains, v)),
    tx.counterpartyAccount.map(RuleCriterion.CounterpartyAccount(_)),
    tx.bankTransactionCode.map(RuleCriterion.BankTransactionCode(_)),
    Some(RuleCriterion.Account(tx.ebAccountUid)),
    Some(RuleCriterion.Direction(tx.isOutflow)),
    Some(RuleCriterion.AmountCompare(AmountMatchOp.Eq, math.abs(tx.amountCents))),
    Some(RuleCriterion.CurrencyIs(tx.currency)),
  ).flatten
}
