package ssbudget.shared.model

import io.circe.Codec
import ssbudget.shared.json.StringId

final case class CardGroupId(value: String) extends AnyVal

object CardGroupId extends StringId[CardGroupId]

/** A set of credit cards sharing a single credit limit. Its remaining limit is mirrored onto a user-chosen app [[Account]] (`accountId`, which the
  * user links/unlinks freely; `None` until linked). When linked, that account's `balanceSource` is `CardGroup` and its balance is recomputed on sync
  * as `sum(member available balances) - (memberCount - 1) * limit`.
  */
final case class CardGroup(
    id: CardGroupId,
    name: String,
    limitCents: Long,
    currency: Currency,
    accountId: Option[AccountId], // the app account whose balance mirrors the remaining limit (None until the user links one)
) derives Codec.AsObject {

  /** Remaining shared limit given each member card's available balance (cents). Each card reports the full limit minus its own usage, so summing
    * double-counts the limit (memberCount − 1) times. None when no member has a synced balance yet.
    */
  def remaining(memberAvailableCents: List[Long]): Option[Long] =
    if memberAvailableCents.isEmpty then None
    else Some(memberAvailableCents.sum - (memberAvailableCents.size - 1).toLong * limitCents)
}
