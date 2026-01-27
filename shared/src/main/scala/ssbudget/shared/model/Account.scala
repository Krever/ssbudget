package ssbudget.shared.model

import io.circe.Codec
import ssbudget.shared.json.StringId

final case class AccountId(value: String) extends AnyVal

object AccountId extends StringId[AccountId]

final case class Account(
    id: AccountId,
    name: String,
    currency: Currency,
) derives Codec.AsObject
