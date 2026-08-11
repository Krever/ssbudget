package ssbudget.e2e

import cats.effect.unsafe.implicits.global
import ssbudget.shared.model.*

import java.time.Instant
import java.util.UUID

/** Seeds bank transactions straight into the running backend's database.
  *
  * The UI has no way to create one — transactions only arrive through an Enable Banking import — so any behaviour that depends on them (rule badges,
  * category spend figures, the category drill-downs) needs this.
  */
object TransactionSeed {

  private def repos = TestServers.repos

  /** A connection to hang transactions off, created once and reused (`connection_id` is a foreign key). */
  private lazy val connection: BankConnection = {
    val conn = BankConnection(
      id = BankConnectionId(UUID.randomUUID().toString),
      aspspName = "E2E Test Bank",
      aspspCountry = "PL",
      sessionId = Some("e2e-session"),
      status = ConnectionStatus.Active,
      validUntil = None,
      authState = None,
      createdAt = Instant.now(),
    )
    repos.bankConnections.create(conn).unsafeRunSync()
    conn
  }

  def accountUid: String = "e2e-acc-uid"

  /** Insert one booked outflow. `bookedAt` defaults to now, which puts it in the current period. */
  def addTransaction(
      counterparty: String,
      amountCents: Long,
      bookedAt: Instant = Instant.now(),
      categoryId: Option[CategoryId] = None,
      categorySource: Option[CategorySource] = None,
  ): BankTransaction = {
    val dedup = UUID.randomUUID().toString
    val tx    = BankTransaction(
      id = BankTransactionId(UUID.randomUUID().toString),
      connectionId = connection.id,
      ebAccountUid = accountUid,
      entryReference = Some(dedup),
      dedupKey = dedup,
      amountCents = amountCents,
      currency = Currency.PLN,
      status = TransactionStatus.Booked,
      bookedAt = bookedAt,
      counterpartyName = Some(counterparty),
      counterpartyAccount = None,
      remittance = None,
      bankTransactionCode = None,
      categoryId = categoryId,
      rawJson = "{}",
      importedAt = Instant.now(),
      internal = false,
      categorySource = categorySource,
    )
    repos.bankTransactions.insertNew(tx).unsafeRunSync()
    tx
  }

  /** Create a category and return its id. A `budgetType` is what surfaces it on the Budget page's Category Budgets card. */
  def addCategory(name: String, budgetType: Option[CategoryBudgetType] = None): CategoryId = {
    val cat = Category(CategoryId(UUID.randomUUID().toString), name, None, budgetType)
    repos.categories.create(cat).unsafeRunSync()
    cat.id
  }

  /** Create a rule matching a counterparty name. Priority is appended after the existing rules so ordering stays deterministic. */
  def addRule(name: String, categoryId: CategoryId, counterpartyContains: String): ClassificationRule = {
    val existing = repos.classificationRules.findAll.unsafeRunSync()
    val rule     = ClassificationRule(
      id = ClassificationRuleId(UUID.randomUUID().toString),
      name = name,
      categoryId = categoryId,
      priority = existing.size,
      criteria = List(RuleCriterion.CounterpartyName(TextMatchOp.Contains, counterpartyContains)),
      createdAt = Instant.now(),
    )
    repos.classificationRules.create(rule).unsafeRunSync()
    rule
  }
}
