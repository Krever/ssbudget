package ssbudget.shared.model

import io.circe.Codec
import ssbudget.shared.json.StringId

import java.time.Instant

final case class PeriodId(value: String) extends AnyVal
object PeriodId                          extends StringId[PeriodId]

final case class Period(
    id: PeriodId,
    startDate: Instant,
    endDate: Option[Instant], // None until period is closed
) derives Codec.AsObject
