package ssbudget.shared.model

import io.circe.Codec
import ssbudget.shared.json.StringId

final case class SavingsAccountId(value: String) extends AnyVal

object SavingsAccountId extends StringId[SavingsAccountId]

final case class SavingsAccount(
    id: SavingsAccountId,
    name: String,
    currency: Currency,
    currentBalance: Long,        // in cents, editable directly
    plannedMonthly: Option[Long], // optional monthly target in cents
) derives Codec.AsObject
