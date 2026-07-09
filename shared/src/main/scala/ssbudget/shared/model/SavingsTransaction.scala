package ssbudget.shared.model

import io.circe.Codec
import ssbudget.shared.json.StringId

import java.time.Instant

final case class SavingsTransactionId(value: String) extends AnyVal

object SavingsTransactionId extends StringId[SavingsTransactionId]

final case class SavingsTransaction(
    id: SavingsTransactionId,
    accountId: AccountId, // references a savings-role Account
    periodId: PeriodId,
    amount: Long,         // positive = inflow, negative = outflow
    note: Option[String], // optional context
    createdAt: Instant,
) derives Codec.AsObject
