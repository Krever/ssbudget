package ssbudget.shared.rules

import ssbudget.shared.model.*

/** Pure matching logic shared by the backend (applying rules) and the frontend (live preview in the rule modal) — one source of truth. */
object RuleMatcher {

  /** The form in which text is compared: no outer whitespace, inner whitespace collapsed, no case. Public, so that code which groups transactions as
    * rule candidates uses the same form as the match.
    */
  def normText(s: String): String         = s.trim.replaceAll("\\s+", " ").toUpperCase
  private def normIban(s: String): String = s.replaceAll("\\s", "").toUpperCase

  /** The text fields of one transaction in the form for comparison, each made one time and only when a criterion reads it. */
  final private class TxText(val tx: BankTransaction) {
    lazy val name: Option[String]       = tx.counterpartyName.map(normText)
    lazy val remittance: Option[String] = tx.remittance.map(normText)
    lazy val iban: Option[String]       = tx.counterpartyAccount.map(normIban)
  }

  /** One criterion as a test of a transaction. The value of the criterion is brought to the form for comparison HERE, one time, and not at each
    * comparison: to apply 300 rules to 6,000 transactions is about two million comparisons, and the form is made with regular expressions.
    */
  private def test(c: RuleCriterion): TxText => Boolean = {
    def text(op: TextMatchOp, value: String, field: TxText => Option[String]): TxText => Boolean = {
      val wanted = normText(value)
      op match {
        case TextMatchOp.Contains => t => field(t).exists(_.contains(wanted))
        case TextMatchOp.Equals   => t => field(t).contains(wanted)
      }
    }
    c match {
      case RuleCriterion.CounterpartyName(op, v)   => text(op, v, _.name)
      case RuleCriterion.Remittance(op, v)         => text(op, v, _.remittance)
      case RuleCriterion.CounterpartyAccount(iban) => val wanted = normIban(iban); _.iban.contains(wanted)
      case RuleCriterion.BankTransactionCode(code) => _.tx.bankTransactionCode.contains(code)
      case RuleCriterion.Account(uid)              => _.tx.ebAccountUid == uid
      case RuleCriterion.Direction(outflow)        => _.tx.isOutflow == outflow
      case RuleCriterion.AmountCompare(op, a)      =>
        op match {
          case AmountMatchOp.Lt => t => math.abs(t.tx.amountCents) < a
          case AmountMatchOp.Eq => t => math.abs(t.tx.amountCents) == a
          case AmountMatchOp.Gt => t => math.abs(t.tx.amountCents) > a
        }
      case RuleCriterion.AmountBetween(lo, hi)     => t => { val a = math.abs(t.tx.amountCents); a >= lo && a <= hi }
      case RuleCriterion.CurrencyIs(cur)           => _.tx.currency == cur
    }
  }

  /** All criteria must match (AND). Empty criteria never match: a rule must have at least one condition. */
  private def testAll(criteria: List[RuleCriterion]): TxText => Boolean = {
    val tests = criteria.map(test)
    t => tests.nonEmpty && tests.forall(_(t))
  }

  /** Criteria made ready for many transactions: use this, and not [[matches]], in a loop over transactions. */
  def compile(criteria: List[RuleCriterion]): BankTransaction => Boolean = {
    val all = testAll(criteria)
    tx => all(new TxText(tx))
  }

  /** A rule list (in ascending priority) made ready for many transactions: gives the first rule whose criteria all match. Internal transfers are
    * never categorized. Use this, and not [[firstMatch]], in a loop over transactions.
    */
  def compileRules(rulesByPriority: List[ClassificationRule]): BankTransaction => Option[ClassificationRule] = {
    val tests = rulesByPriority.map(r => r -> testAll(r.criteria))
    tx =>
      if tx.internal then None
      else {
        val text = new TxText(tx)
        tests.collectFirst { case (rule, matches) if matches(text) => rule }
      }
  }

  def matchesOne(c: RuleCriterion, tx: BankTransaction): Boolean = test(c)(new TxText(tx))

  /** For one transaction. All criteria must match (AND). Empty criteria never match. */
  def matches(criteria: List[RuleCriterion], tx: BankTransaction): Boolean = compile(criteria)(tx)

  /** For one transaction: the first rule (by the given ascending-priority order) whose criteria all match. */
  def firstMatch(rulesByPriority: List[ClassificationRule], tx: BankTransaction): Option[ClassificationRule] =
    if tx.internal then None
    else {
      // Each rule is made ready only when the search comes to it: for one transaction, the rules after the match are never read.
      val text = new TxText(tx)
      rulesByPriority.find(rule => testAll(rule.criteria)(text))
    }

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
