package ssbudget.backend.banking

import cats.data.EitherT
import cats.effect.IO
import cats.implicits.*
import ssbudget.backend.db.Repositories
import ssbudget.backend.service.AppClock
import ssbudget.backend.db.repository.RuleWrite
import ssbudget.shared.api.{ImportRulesRequest, ImportRulesResult, RuleExport, RulePreviewRequest, RulePreviewResponse, RulesExport}
import ssbudget.shared.model.*
import ssbudget.shared.rules.RuleMatcher

import java.time.Instant
import java.util.UUID

/** One change to the rule list. It is the only input of [[CategorizationService.previewChange]] and [[CategorizationService.applyChange]], so what a
  * preview shows is what the write does.
  *
  * `position` is the 0-based index in the rule list (0 = evaluated first). `None` puts a new rule last and keeps an updated rule where it is. In an
  * `Update`, a field that is `None` keeps its value.
  */
enum RuleChange {
  case Create(name: String, categoryId: CategoryId, criteria: List[RuleCriterion], position: Option[Int] = None)
  case Update(
      id: ClassificationRuleId,
      name: Option[String] = None,
      categoryId: Option[CategoryId] = None,
      criteria: Option[List[RuleCriterion]] = None,
      position: Option[Int] = None,
  )
  case Delete(id: ClassificationRuleId)
}

/** A rule as it is after a write, with its index in the rule list. */
final case class RuleWritten(rule: ClassificationRule, position: Int)

/** The effect of a [[RuleChange]] on the stored transactions.
  *
  * @param matched
  *   transactions that the criteria of the rule match (none for a delete)
  * @param changes
  *   each transaction that gets a different category, with the new category (`None` = becomes uncategorized)
  * @param keptManual
  *   matched transactions that keep a different, manual category
  * @param takenByOtherRule
  *   matched transactions that an earlier rule categorizes, with that rule
  * @param internalSkipped
  *   matched internal transfers; rules never categorize these
  */
final case class RuleImpact(
    matched: List[BankTransaction],
    changes: List[(BankTransaction, Option[CategoryId])],
    keptManual: List[BankTransaction],
    takenByOtherRule: List[(BankTransaction, ClassificationRule)],
    internalSkipped: Int,
)

/** The writes that categorize transactions one change at a time: manual categories, and the create / update / delete of a rule with its preview. The
  * REST routes and the MCP tools both call this service, so they obey the same validation and the same "apply all rules again after a rule change"
  * contract. (Rule import, rule reorder and category delete are still in `Routes`.)
  */
class CategorizationService(repos: Repositories, ruleEngine: RuleEngineService, clock: AppClock = AppClock.system) {

  import CategorizationService.*

  /** Sets (`Some`) or clears (`None`) the MANUAL category of each transaction, in one database transaction. Nothing is written unless each
    * transaction and each category exists. Returns how many were written.
    */
  def setCategories(assignments: List[(BankTransactionId, Option[CategoryId])]): Result[Int] =
    for {
      known   <- repos.categories.findAll.map(_.map(_.id).toSet)
      found   <- repos.bankTransactions.findByIds(assignments.map(_._1).distinct).map(_.map(_.id).toSet)
      problems = assignments.flatMap { case (tx, category) =>
                   if !found(tx) then Some(s"Transaction not found: ${tx.value}")
                   else category.filterNot(known).map(c => s"Category not found: ${c.value}")
                 }.distinct
      result  <- if problems.nonEmpty then IO.pure(Left(problems.take(10).mkString("; ")))
                 else
                   repos.bankTransactions
                     .applyCategoryUpdates(assignments.map { case (tx, category) => (tx, category, category.map(_ => CategorySource.Manual)) })
                     .as(Right(assignments.size))
    } yield result

  /** The one-transaction form of [[setCategories]]; returns the transaction as it is after the write. */
  def setCategory(id: BankTransactionId, categoryId: Option[CategoryId]): Result[BankTransaction] =
    (for {
      _  <- EitherT[IO, String, Int](setCategories(List(id -> categoryId)))
      tx <- EitherT.fromOptionF(repos.bankTransactions.findById(id), s"Transaction not found: ${id.value}")
    } yield tx).value

  /** Calculates what a rule change does to the stored transactions. It writes nothing. */
  def previewChange(change: RuleChange): Result[RuleImpact] =
    (for {
      resolved <- EitherT[IO, String, Resolved](resolve(change))
      txs      <- EitherT.liftF(repos.bankTransactions.list(None, None, None))
    } yield impact(resolved.rulesAfter, resolved.rule, txs)).value

  /** Writes a rule change and applies all rules again. Returns the rule that was created or updated; `None` for a delete. */
  def applyChange(change: RuleChange): Result[Option[RuleWritten]] =
    (for {
      resolved <- EitherT[IO, String, Resolved](resolve(change))
      // The order is written only when the change asks for a position: without one, the stored priorities already give `rulesAfter`. The rule
      // and the order go in one transaction.
      order     = Option.when(resolved.moved)(resolved.rulesAfter.map(_.id))
      _        <- EitherT.liftF((change, resolved.rule) match {
                    case (_: RuleChange.Create, Some(rule)) => repos.classificationRules.write(RuleWrite.Create(rule), order)
                    case (_: RuleChange.Update, Some(rule)) => repos.classificationRules.write(RuleWrite.Update(rule), order)
                    case (RuleChange.Delete(id), _)         => repos.classificationRules.write(RuleWrite.Delete(id), order)
                    case _                                  => IO.unit
                  })
      _        <- EitherT.liftF(ruleEngine.applyRules())
    } yield resolved.rule.map { rule =>
      val position = resolved.rulesAfter.indexWhere(_.id == rule.id)
      RuleWritten(if resolved.moved then rule.copy(priority = position) else rule, position)
    }).value

  /** The rule that a create or an update wrote: the answer of the REST routes. */
  def writeRule(change: RuleChange): Result[ClassificationRule] =
    applyChange(change).map(_.flatMap(_.map(_.rule).toRight("The change gave no rule")))

  /** Writes a new order of the rules and applies all rules again. */
  def reorder(orderedIds: List[ClassificationRuleId]): IO[List[ClassificationRule]] =
    for {
      _     <- repos.classificationRules.reorder(orderedIds)
      _     <- ruleEngine.applyRules()
      rules <- repos.classificationRules.findAll
    } yield rules

  /** Export all rules in portable form (category carried by name, order = priority). */
  def exportRules: IO[RulesExport] =
    for {
      rules <- repos.classificationRules.findAll // ordered by priority
      cats  <- repos.categories.findAll
    } yield {
      val nameById = cats.map(c => c.id -> c.name).toMap
      RulesExport(version = 1, rules = rules.map(r => RuleExport(r.name, nameById.getOrElse(r.categoryId, ""), r.criteria)))
    }

  /** Import a rules bundle atomically: (optionally) clear existing rules, create any categories referenced by name, then create the rules in order.
    */
  def importRules(req: ImportRulesRequest): IO[ImportRulesResult] =
    for {
      existingCats <- repos.categories.findAll
      now          <- clock.now
      // Each category that a rule names is found by name (case does not count). One that does not exist yet is made.
      known         = existingCats.map(c => c.name.toLowerCase -> c.id).toMap
      newCategories = req.bundle.rules
                        .map(_.categoryName)
                        .distinctBy(_.toLowerCase)
                        .filterNot(name => known.contains(name.toLowerCase))
                        .map(name => Category(CategoryId(UUID.randomUUID().toString), name, None))
      catByName     = known ++ newCategories.map(c => c.name.toLowerCase -> c.id)
      rulesFrom     = (base: Int) =>
                        req.bundle.rules.zipWithIndex.map { case (r, idx) =>
                          ClassificationRule(
                            ClassificationRuleId(UUID.randomUUID().toString),
                            r.name,
                            catByName(r.categoryName.toLowerCase),
                            base + idx,
                            r.criteria,
                            now,
                          )
                        }
      // One transaction: a failure leaves the rules and the categories as they were (with `replace`, not an empty rule list).
      _            <- repos.classificationRules.importAll(req.replace, newCategories, rulesFrom)
      _            <- ruleEngine.applyRules()
    } yield ImportRulesResult(req.bundle.rules.size, newCategories.size)

  /** Live rule preview: run the shared matcher over all stored transactions server-side (the browser no longer holds them). */
  def previewRule(req: RulePreviewRequest): IO[RulePreviewResponse] =
    repos.bankTransactions.list(None, None, None).map { all =>
      val matched = if req.criteria.isEmpty then Nil else all.filter(RuleMatcher.compile(req.criteria))
      RulePreviewResponse(matched.size, all.size, matched.take(200))
    }

  /** Checks a change against the stored rules and categories, and gives the rule list as it is after the change. */
  private def resolve(change: RuleChange): Result[Resolved] =
    for {
      rules      <- repos.classificationRules.findAll
      categories <- repos.categories.findAll.map(_.map(_.id).toSet)
      now        <- clock.now
    } yield resolved(rules, categories, change, ClassificationRuleId(UUID.randomUUID().toString), now)
}

object CategorizationService {

  type Result[T] = IO[Either[String, T]]

  /** The largest number of criteria in one rule. A rule that needs more is not an "obvious" rule. */
  val maxCriteria = 10

  private val maxTextLength = 200

  /** @param rule
    *   the rule that the change adds or edits (`None` for a delete)
    * @param moved
    *   the change sets a position, so the order must be written
    */
  final private[banking] case class Resolved(rulesAfter: List[ClassificationRule], rule: Option[ClassificationRule], moved: Boolean)

  private def insertAt(rules: List[ClassificationRule], rule: ClassificationRule, position: Int): List[ClassificationRule] = {
    val (before, after) = rules.splitAt(position.max(0).min(rules.size))
    before ++ (rule :: after)
  }

  /** Criteria must be able to be false. A text criterion with a blank value matches every transaction that has the field, and such a rule would take
    * all the transactions below it.
    */
  def validateCriteria(criteria: List[RuleCriterion]): Either[String, Unit] = {
    def text(label: String, value: String): Option[String] =
      if value.trim.isEmpty then Some(s"$label must not be blank")
      else if value.length > maxTextLength then Some(s"$label must not be longer than $maxTextLength characters")
      else None

    val problems = criteria.flatMap {
      case RuleCriterion.CounterpartyName(_, v)    => text("counterpartyName value", v)
      case RuleCriterion.Remittance(_, v)          => text("remittance value", v)
      case RuleCriterion.CounterpartyAccount(iban) => text("counterpartyAccount iban", iban)
      case RuleCriterion.BankTransactionCode(code) => text("bankTransactionCode code", code)
      case RuleCriterion.Account(uid)              => text("account ebAccountUid", uid)
      case RuleCriterion.AmountCompare(_, a)       => Option.when(a < 0)("amountCompare absCents must not be negative")
      case RuleCriterion.AmountBetween(lo, hi)     => Option.when(lo < 0 || hi < lo)("amountBetween needs 0 <= min <= max")
      case RuleCriterion.Direction(_)              => None
      case RuleCriterion.CurrencyIs(_)             => None
    }
    if criteria.isEmpty then Left("A rule must have at least one criterion")
    else if criteria.size > maxCriteria then Left(s"A rule must not have more than $maxCriteria criteria")
    else problems.headOption.toLeft(())
  }

  private def validateRule(rule: ClassificationRule, categories: Set[CategoryId]): Either[String, ClassificationRule] =
    if rule.name.isEmpty then Left("A rule must have a name")
    else if rule.name.length > maxTextLength then Left(s"A rule name must not be longer than $maxTextLength characters")
    else if !categories(rule.categoryId) then Left(s"Category not found: ${rule.categoryId.value}")
    else validateCriteria(rule.criteria).as(rule)

  /** The pure part of a rule change: the checks, and the rule list after it. `newId` and `now` are used only by a `Create`. */
  private[banking] def resolved(
      rules: List[ClassificationRule],
      categories: Set[CategoryId],
      change: RuleChange,
      newId: ClassificationRuleId,
      now: Instant,
  ): Either[String, Resolved] = {
    def existing(id: ClassificationRuleId) = rules.find(_.id == id).toRight(s"Rule not found: ${id.value}")
    change match {
      case RuleChange.Create(name, categoryId, criteria, position)     =>
        // One above the last priority, as the stored order has it; a reorder writes the real index.
        val rule = ClassificationRule(newId, name.trim, categoryId, rules.lastOption.fold(0)(_.priority + 1), criteria, now)
        validateRule(rule, categories).map(r => Resolved(insertAt(rules, r, position.getOrElse(rules.size)), Some(r), position.isDefined))
      case RuleChange.Update(id, name, categoryId, criteria, position) =>
        for {
          old  <- existing(id)
          rule <- validateRule(
                    old.copy(
                      name = name.fold(old.name)(_.trim),
                      categoryId = categoryId.getOrElse(old.categoryId),
                      criteria = criteria.getOrElse(old.criteria),
                    ),
                    categories,
                  )
        } yield Resolved(
          insertAt(rules.filterNot(_.id == id), rule, position.getOrElse(rules.indexWhere(_.id == id))),
          Some(rule),
          position.isDefined,
        )
      case RuleChange.Delete(id)                                       =>
        existing(id).map(_ => Resolved(rules.filterNot(_.id == id), None, moved = false))
    }
  }

  /** Compares what each transaction has now with what `rulesAfter` would give it, by the decision of the engine itself
    * ([[RuleEngineService.winner]]).
    */
  def impact(rulesAfter: List[ClassificationRule], candidate: Option[ClassificationRule], txs: List[BankTransaction]): RuleImpact = {
    // One engine decision per transaction that a rule can change; both lists below come from it.
    val decide  = RuleEngineService.decision(rulesAfter)
    val winners = txs.flatMap(tx => decide(tx).map(tx -> _))
    val changes = winners.collect { case (tx, rule) if rule.map(_.categoryId) != tx.categoryId => (tx, rule.map(_.categoryId)) }
    candidate match {
      case None       => RuleImpact(Nil, changes, Nil, Nil, 0)
      case Some(rule) =>
        val matches = RuleMatcher.compile(rule.criteria)
        val matched = txs.filter(matches)
        RuleImpact(
          matched = matched,
          changes = changes,
          keptManual = matched.filter(tx => tx.categorySource.contains(CategorySource.Manual) && !tx.categoryId.contains(rule.categoryId)),
          takenByOtherRule = winners.collect { case (tx, Some(winner)) if winner.id != rule.id && matches(tx) => (tx, winner) },
          internalSkipped = matched.count(_.internal),
        )
    }
  }
}
