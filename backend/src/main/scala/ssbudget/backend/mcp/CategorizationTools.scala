package ssbudget.backend.mcp

import cats.data.EitherT
import cats.effect.IO
import cats.implicits.*
import io.circe.syntax.*
import io.circe.{Decoder, Json, JsonObject}
import ssbudget.backend.banking.{CategorizationService, RuleChange, RuleImpact, RuleWritten}
import ssbudget.backend.db.repository.TransactionFilter
import ssbudget.backend.db.Repositories
import ssbudget.shared.api.CategoryFilter
import ssbudget.shared.model.*
import ssbudget.shared.rules.RuleMatcher

import java.util.UUID

/** The MCP tools for transaction categorization, and nothing more. This list is the full power of an MCP token: it can read transactions, categories
  * and rules, and it can change categories and rules. There is deliberately no tool that deletes a category, reads a balance or touches a different
  * part of the app.
  *
  * All writes go through [[CategorizationService]], the same code that the web UI calls.
  */
final class CategorizationTools(repos: Repositories, categorization: CategorizationService) {

  import CategorizationTools.*

  val all: List[McpTool] = List(
    McpTool(
      name = "list_uncategorized",
      description = "One batch of the transactions that have no category, newest first, and the total that remain. `groups` counts ALL remaining " +
        "transactions by counterparty (or remittance text when there is no counterparty): a large group is a candidate for a rule. Internal " +
        "transfers between the user's own accounts are not included; they never get a category.",
      inputSchema = schema(
        "limit"  -> Json.obj("type" := "integer", "minimum" := 1, "maximum" := MaxBatch, "description" := s"Batch size, default $DefaultBatch"),
        "offset" -> Json.obj("type" := "integer", "minimum" := 0, "description" := "Skip this many, to pass transactions the user left open"),
      )(),
      readOnly = true,
      run = listUncategorized,
    ),
    McpTool(
      name = "list_categories",
      description = "All categories with their ids and how many transactions each one has.",
      inputSchema = schema()(),
      readOnly = true,
      run = _ => listCategories,
    ),
    McpTool(
      name = "list_rules",
      description = "Categorization rules in evaluation order. For each transaction the FIRST rule whose criteria all match sets the category; " +
        "`position` 0 is evaluated first. A manual category always wins over a rule. There can be hundreds of rules: give `query` (text that " +
        "occurs in the rule name, its category or its criteria, for example a merchant name) or `categoryId` to get only the related rules. " +
        s"Not more than $MaxRules rules come back; `total` counts all that match.",
      inputSchema = schema(
        "query"      -> Json.obj("type" := "string"),
        "categoryId" -> Json.obj("type" := "string"),
      )(),
      readOnly = true,
      run = listRules,
    ),
    McpTool(
      name = "preview_rule",
      description = "Shows what a rule change would do to ALL stored transactions, and writes nothing. Call it before create_rule, update_rule " +
        "and delete_rule. Without `ruleId` it previews a new rule; with `ruleId` an update of that rule (omitted fields keep their value); with " +
        "`ruleId` and `delete: true` its removal. The result gives: `matched` (transactions the criteria match) with `matchedByCategory` (the categories they have NOW — how such transactions were categorized before), `changes` (transactions that get a " +
        "different category, grouped in `transitions` — look for moves OUT of a category that was correct), `keptManual` (matched, but a manual " +
        "category stays) and `takenByEarlierRule` (matched, but an earlier rule wins; use `position` to go before it).",
      inputSchema = schema(
        "ruleId"     -> Json.obj("type" := "string", "description" := "Existing rule to update or delete; omit for a new rule"),
        "categoryId" -> Json.obj("type" := "string"),
        "criteria"   -> criteriaSchema,
        "position"   -> positionSchema,
        "delete"     -> Json.obj("type" := "boolean"),
      )(),
      readOnly = true,
      run = previewRule,
    ),
    McpTool(
      name = "create_rule",
      description = "Creates a rule and applies all rules again to all transactions. Only after the user confirmed the proposal.",
      inputSchema = schema(
        "name"       -> Json.obj("type" := "string", "description" := "Short label, usually the merchant"),
        "categoryId" -> Json.obj("type" := "string"),
        "criteria"   -> criteriaSchema,
        "position"   -> positionSchema,
      )("name", "categoryId", "criteria"),
      readOnly = false,
      run = writeRule(_, delete = false, expectsRuleId = false),
    ),
    McpTool(
      name = "update_rule",
      description = "Changes a rule (omitted fields keep their value) and applies all rules again. Only after the user confirmed the proposal.",
      inputSchema = schema(
        "ruleId"     -> Json.obj("type" := "string"),
        "name"       -> Json.obj("type" := "string"),
        "categoryId" -> Json.obj("type" := "string"),
        "criteria"   -> criteriaSchema,
        "position"   -> positionSchema,
      )("ruleId"),
      readOnly = false,
      run = writeRule(_, delete = false, expectsRuleId = true),
    ),
    McpTool(
      name = "delete_rule",
      description = "Removes a rule and applies the remaining rules again: transactions that only this rule categorized become uncategorized. " +
        "Only after the user confirmed the proposal.",
      inputSchema = schema("ruleId" -> Json.obj("type" := "string"))("ruleId"),
      readOnly = false,
      destructive = true,
      run = writeRule(_, delete = true, expectsRuleId = true),
    ),
    McpTool(
      name = "set_categories",
      description = s"Sets a MANUAL category on up to $MaxBatch transactions; rules never change a manual category afterwards. `categoryId: null` " +
        "clears the category. Nothing is written unless every transaction and category exists. Use it for one-off transactions; for a merchant " +
        "that comes back, a rule is better. Only after the user confirmed the proposal.",
      inputSchema = schema(
        "assignments" -> Json.obj(
          "type"     := "array",
          "minItems" := 1,
          "maxItems" := MaxBatch,
          "items"    := schema(
            "transactionId" -> Json.obj("type" := "string"),
            "categoryId"    -> Json.obj("type" := List("string", "null")),
          )("transactionId", "categoryId"),
        ),
      )("assignments"),
      readOnly = false,
      run = setCategories,
    ),
    McpTool(
      name = "create_category",
      description = "Creates a category. Only when no existing category fits AND the user agreed to the new one.",
      inputSchema = schema("name" -> Json.obj("type" := "string", "maxLength" := MaxCategoryName))("name"),
      readOnly = false,
      run = createCategory,
    ),
  )

  /** The triage backlog as the Transactions page defines it (`CategoryFilter.Uncategorized`), newest first, with its full size. */
  private def uncategorized(limit: Option[Int]): IO[(List[BankTransaction], Int)] =
    repos.bankTransactions
      .query(TransactionFilter(category = Some(CategoryFilter.Uncategorized)), sort = "date", asc = false, limit = limit)
      .map { case (rows, total, _) => (rows, total) }

  private def uncategorizedCount: IO[Int] = uncategorized(Some(0)).map(_._2)

  private def categoryNames: IO[Map[CategoryId, String]] = repos.categories.findAll.map(_.map(c => c.id -> c.name).toMap)

  private def listUncategorized(args: JsonObject): ToolResult =
    (arg[Option[Int]](args, "limit"), arg[Option[Int]](args, "offset")).tupled.traverse { case (limit, offset) =>
      // All rows are read, not one page: `groups` counts everything that remains.
      uncategorized(None).map { case (txs, total) =>
        val from          = offset.getOrElse(0).max(0)
        val page          = txs.slice(from, from + limit.getOrElse(DefaultBatch).max(1).min(MaxBatch))
        val groups        = txs
          .groupBy(groupKey)
          .toList
          .collect { case ((field, label), members) if members.size > 1 => (field, label, members.size) }
          .sortBy(g => (-g._3, g._2))
          .take(MaxGroups)
        Json.obj(
          "total"        := total,
          "offset"       := from,
          "transactions" := page.map(txJson(_, Map.empty)),
          "groups"       := groups.map { case (field, label, count) => Json.obj("field" := field, "text" := label, "count" := count) },
        )
      }
    }

  private def listCategories: ToolResult =
    for {
      cats   <- repos.categories.findAll
      counts <- repos.bankTransactions.countByCategory.map(_.toMap)
    } yield Right(
      cats
        .sortBy(_.name.toLowerCase)
        .map(c => Json.obj("id" := c.id, "name" := c.name, "isBudget" := c.budgetType.isDefined, "transactions" := counts.getOrElse(c.id, 0)))
        .asJson,
    )

  private def listRules(args: JsonObject): ToolResult =
    (arg[Option[String]](args, "query"), arg[Option[CategoryId]](args, "categoryId")).tupled.traverse { case (query, category) =>
      for {
        rules <- repos.classificationRules.findAll
        names <- categoryNames
      } yield {
        val needle   = query.map(_.trim.toLowerCase).filter(_.nonEmpty)
        val selected = rules.zipWithIndex.filter { case (r, _) =>
          category.forall(_ == r.categoryId) && needle.forall { n =>
            // The criteria are searched in their JSON form, so each text, IBAN and code in them is found.
            r.name.toLowerCase.contains(n) || names.get(r.categoryId).exists(_.toLowerCase.contains(n)) ||
            r.criteria.asJson.noSpaces.toLowerCase.contains(n)
          }
        }
        Json.obj("total" := selected.size, "rules" := selected.take(MaxRules).map { case (r, i) => ruleJson(r, i, names) })
      }
    }

  /** The rule change that the arguments describe. `preview_rule` and the three write tools all read it this way, so a preview and its write get the
    * same change from the same arguments.
    */
  private def ruleChange(args: JsonObject, delete: Boolean): Either[String, RuleChange] =
    for {
      ruleId   <- arg[Option[ClassificationRuleId]](args, "ruleId")
      name     <- arg[Option[String]](args, "name")
      category <- arg[Option[CategoryId]](args, "categoryId")
      criteria <- arg[Option[List[RuleCriterion]]](args, "criteria")
      position <- arg[Option[Int]](args, "position")
      change   <- (ruleId, delete) match {
                    case (Some(id), true)  => Right(RuleChange.Delete(id))
                    case (None, true)      => Left("A delete needs a `ruleId`")
                    case (Some(id), false) => Right(RuleChange.Update(id, name, category, criteria, position))
                    case (None, false)     =>
                      (category, criteria).tupled
                        .toRight("A new rule needs `categoryId` and `criteria`")
                        // A preview of a new rule has no name yet; the write tool requires one in its schema and the service refuses a blank one.
                        .map { case (cat, crit) => RuleChange.Create(name.getOrElse(""), cat, crit, position) }
                  }
    } yield change

  private def previewRule(args: JsonObject): ToolResult =
    (for {
      delete <- EitherT.fromEither[IO](arg[Option[Boolean]](args, "delete"))
      change <- EitherT.fromEither[IO](ruleChange(args, delete.getOrElse(false)).map {
                  // Only a preview may have no name.
                  case c: RuleChange.Create if c.name.trim.isEmpty => c.copy(name = "(new rule)")
                  case other                                       => other
                })
      impact <- EitherT[IO, String, RuleImpact](categorization.previewChange(change).map(_.left.map(_.message)))
      names  <- EitherT.liftF(categoryNames)
    } yield impactJson(impact, names)).value

  /** `create_rule`, `update_rule` and `delete_rule`: they differ only in the change that they are permitted to make. */
  private def writeRule(args: JsonObject, delete: Boolean, expectsRuleId: Boolean): ToolResult =
    (for {
      _         <- EitherT.cond[IO](
                     args("ruleId").exists(!_.isNull) == expectsRuleId,
                     (),
                     if expectsRuleId then "`ruleId` is required" else "A new rule has no `ruleId`",
                   )
      change    <- EitherT.fromEither[IO](ruleChange(args, delete))
      written   <- EitherT[IO, String, Option[RuleWritten]](categorization.applyChange(change).map(_.left.map(_.message)))
      names     <- EitherT.liftF(categoryNames)
      remaining <- EitherT.liftF(uncategorizedCount)
    } yield written match {
      case Some(w) => Json.obj("rule" := ruleJson(w.rule, w.position, names), "uncategorizedRemaining" := remaining)
      case None    => Json.obj("deleted" := args("ruleId"), "uncategorizedRemaining" := remaining)
    }).value

  private def setCategories(args: JsonObject): ToolResult =
    (for {
      assignments <- EitherT.fromEither[IO](arg[List[Assignment]](args, "assignments"))
      _           <- EitherT.cond[IO](assignments.nonEmpty, (), "`assignments` is empty")
      _           <- EitherT.cond[IO](assignments.size <= MaxBatch, (), s"Not more than $MaxBatch assignments in one call")
      updated     <-
        EitherT[IO, String, Int](categorization.setCategories(assignments.map(a => a.transactionId -> a.categoryId)).map(_.left.map(_.message)))
          .leftMap(_ + ". Nothing was written.")
      remaining   <- EitherT.liftF(uncategorizedCount)
    } yield Json.obj("updated" := updated, "uncategorizedRemaining" := remaining)).value

  private def createCategory(args: JsonObject): ToolResult =
    (for {
      name    <- EitherT.fromEither[IO](arg[String](args, "name").map(_.trim))
      _       <- EitherT.cond[IO](name.nonEmpty, (), "`name` must not be blank")
      _       <- EitherT.cond[IO](name.length <= MaxCategoryName, (), s"`name` must not be longer than $MaxCategoryName characters")
      cats    <- EitherT.liftF(repos.categories.findAll)
      _       <- EitherT.fromEither[IO](cats.find(_.name.equalsIgnoreCase(name)).map(c => s"A category with this name exists: ${c.id.value}").toLeft(()))
      category = Category(CategoryId(UUID.randomUUID().toString), name, None)
      _       <- EitherT.liftF(repos.categories.create(category))
    } yield Json.obj("id" := category.id, "name" := category.name)).value
}

object CategorizationTools {

  val DefaultBatch    = 40
  val MaxBatch        = 100
  val MaxGroups       = 30
  val MaxCategoryName = 60
  val MaxRules        = 100

  /** How many transactions a preview lists in full. The counts always cover all of them. */
  val MaxPreviewSample = 40

  private type ToolResult = IO[Either[String, Json]]

  final private case class Assignment(transactionId: BankTransactionId, categoryId: Option[CategoryId]) derives Decoder

  /** Reads one argument. A missing key and `null` are both "not given" for an `Option`. */
  private def arg[T: Decoder](args: JsonObject, name: String): Either[String, T] =
    args(name).getOrElse(Json.Null).as[T].leftMap(f => s"Invalid `$name`: ${f.getMessage}")

  private def schema(properties: (String, Json)*)(required: String*): Json =
    Json.obj(
      "type"                 := "object",
      "properties"           := Json.obj(properties*),
      "required"             := required.toList,
      "additionalProperties" := false,
    )

  private val positionSchema: Json = Json.obj(
    "type"        := "integer",
    "minimum"     := 0,
    "description" := "Index in the rule list, 0 = evaluated first. Omit: a new rule goes last, an updated rule stays where it is",
  )

  /** The stored form of [[RuleCriterion]] (its own circe codec reads it), described for the model. */
  private val criteriaSchema: Json = Json.obj(
    "type"        := "array",
    "minItems"    := 1,
    "maxItems"    := CategorizationService.maxCriteria,
    "description" := ("All criteria must match (AND). Text is compared without case and with whitespace collapsed. Each item is one of: " +
      """{"kind":"counterpartyName","op":"contains"|"equals","value":"..."} · """ +
      """{"kind":"remittance","op":"contains"|"equals","value":"..."} · """ +
      """{"kind":"counterpartyAccount","iban":"..."} · """ +
      """{"kind":"bankTransactionCode","code":"..."} · """ +
      """{"kind":"account","ebAccountUid":"..."} · """ +
      """{"kind":"direction","outflow":true|false} · """ +
      """{"kind":"amountCompare","op":"lt"|"eq"|"gt","absCents":1234} · """ +
      """{"kind":"amountBetween","min":1000,"max":2000} · """ +
      """{"kind":"currencyIs","currency":"PLN"}. Amounts are absolute values in cents."""),
    "items"       := Json.obj("type" := "object", "required" := List("kind")),
  )

  /** A transaction falls in the group of its counterparty, or of its remittance text when the bank gave no counterparty. */
  private def groupKey(tx: BankTransaction): (String, String) = {
    import RuleMatcher.normText
    tx.counterpartyName
      .map(n => ("counterpartyName", normText(n)))
      .orElse(tx.remittance.map(r => ("remittance", normText(r))))
      .getOrElse(("none", "—"))
  }

  /** What the model needs to categorize a transaction, and nothing else: the raw bank payload stays in the database. */
  private def txJson(tx: BankTransaction, names: Map[CategoryId, String]): Json =
    Json
      .obj(
        "id"                  := tx.id,
        "date"                := tx.bookedAt.toString.take(10),
        "amount"              := BigDecimal(tx.amountCents, 2).toString,
        "currency"            := tx.currency,
        "counterpartyName"    := tx.counterpartyName,
        "remittance"          := tx.remittance,
        "counterpartyAccount" := tx.counterpartyAccount,
        "bankTransactionCode" := tx.bankTransactionCode,
        "ebAccountUid"        := tx.ebAccountUid,
        "note"                := tx.note,
        "category"            := tx.categoryId.map(c => names.getOrElse(c, c.value)),
        "categorySource"      := tx.categorySource,
      )
      .dropNullValues

  private def ruleJson(rule: ClassificationRule, position: Int, names: Map[CategoryId, String]): Json = Json.obj(
    "id"         := rule.id,
    "position"   := position,
    "name"       := rule.name,
    "categoryId" := rule.categoryId,
    "category"   := names.getOrElse(rule.categoryId, "(unknown)"),
    "criteria"   := rule.criteria,
  )

  private def impactJson(impact: RuleImpact, names: Map[CategoryId, String]): Json = {
    def name(c: Option[CategoryId]): String = c.map(id => names.getOrElse(id, id.value)).getOrElse("(uncategorized)")

    val transitions = impact.changes
      .groupMapReduce { case (tx, to) => (name(tx.categoryId), name(to)) }(_ => 1)(_ + _)
      .toList
      .sortBy { case (_, count) => -count }
      .map { case ((from, to), count) => Json.obj("from" := from, "to" := to, "count" := count) }

    Json.obj(
      "matched"            := impact.matched.size,
      "matchedByCategory"  := impact.matched
        .groupMapReduce(tx => name(tx.categoryId))(_ => 1)(_ + _)
        .toList
        .sortBy { case (_, count) => -count }
        .map { case (category, count) => Json.obj("category" := category, "count" := count) },
      "changes"            := Json.obj(
        "count"       := impact.changes.size,
        "transitions" := transitions,
        "sample"      := impact.changes.take(MaxPreviewSample).map { case (tx, to) => txJson(tx, names).deepMerge(Json.obj("newCategory" := name(to))) },
      ),
      "keptManual"         := Json.obj(
        "count"  := impact.keptManual.size,
        "sample" := impact.keptManual.take(10).map(txJson(_, names)),
      ),
      "takenByEarlierRule" := Json.obj(
        "count"  := impact.takenByOtherRule.size,
        "sample" := impact.takenByOtherRule.take(10).map { case (tx, rule) => txJson(tx, names).deepMerge(Json.obj("rule" := rule.name)) },
      ),
      "internalSkipped"    := impact.internalSkipped,
    )
  }
}
