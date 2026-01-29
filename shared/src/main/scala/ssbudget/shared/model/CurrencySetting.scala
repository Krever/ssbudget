package ssbudget.shared.model

import io.circe.Codec
import java.time.Instant

final case class CurrencySetting(
    code: Currency,
    name: String,
    isPrimary: Boolean,
    enabledAt: Instant,
) derives Codec.AsObject
