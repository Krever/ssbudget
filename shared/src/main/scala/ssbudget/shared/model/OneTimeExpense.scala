package ssbudget.shared.model

import io.circe.Codec
import ssbudget.shared.json.StringId

import java.time.Instant

final case class OneTimeExpenseId(value: String) extends AnyVal

object OneTimeExpenseId extends StringId[OneTimeExpenseId]

final case class OneTimeExpense(
    id: OneTimeExpenseId,
    name: String,
    amountCents: Long,
    currency: Currency,
    date: Instant,
) derives Codec.AsObject
