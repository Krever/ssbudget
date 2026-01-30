package ssbudget.shared.model

import io.circe.Codec
import ssbudget.shared.json.StringId

import java.time.Instant

final case class ExpenseRecordId(value: String) extends AnyVal
object ExpenseRecordId                          extends StringId[ExpenseRecordId]

final case class ExpenseRecord(
    id: ExpenseRecordId,
    periodId: PeriodId,
    expenseDefId: ExpenseDefId,
    paidAmount: Option[Long], // in cents, None until paid
    paidAt: Option[Instant],  // None until paid
) derives Codec.AsObject
