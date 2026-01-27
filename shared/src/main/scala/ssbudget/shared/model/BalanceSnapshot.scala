package ssbudget.shared.model

import io.circe.Codec
import ssbudget.shared.json.StringId

import java.time.Instant

final case class BalanceSnapshotId(value: String) extends AnyVal
object BalanceSnapshotId                          extends StringId[BalanceSnapshotId]

final case class BalanceSnapshot(
    id: BalanceSnapshotId,
    accountId: AccountId,
    amount: Long, // in cents
    currency: Currency,
    recordedAt: Instant,
) derives Codec.AsObject
