package ssbudget.shared.api

import sttp.tapir.{Codec, CodecFormat, DecodeResult}
import ssbudget.shared.model.*

/** Tapir path codecs for ID types */
object TapirCodecs {

  private def stringIdCodec[T](apply: String => T, unapply: T => String): Codec[String, T, CodecFormat.TextPlain] =
    Codec.string.map(apply)(unapply)

  given Codec[String, AccountId, CodecFormat.TextPlain]            = stringIdCodec(AccountId.apply, _.value)
  given Codec[String, ExpenseDefId, CodecFormat.TextPlain]         = stringIdCodec(ExpenseDefId.apply, _.value)
  given Codec[String, PeriodId, CodecFormat.TextPlain]             = stringIdCodec(PeriodId.apply, _.value)
  given Codec[String, BalanceSnapshotId, CodecFormat.TextPlain]    = stringIdCodec(BalanceSnapshotId.apply, _.value)
  given Codec[String, ExpenseRecordId, CodecFormat.TextPlain]      = stringIdCodec(ExpenseRecordId.apply, _.value)
  given Codec[String, SavingsTransactionId, CodecFormat.TextPlain] = stringIdCodec(SavingsTransactionId.apply, _.value)
  given Codec[String, OneTimeExpenseId, CodecFormat.TextPlain]     = stringIdCodec(OneTimeExpenseId.apply, _.value)
  given Codec[String, BankConnectionId, CodecFormat.TextPlain]     = stringIdCodec(BankConnectionId.apply, _.value)
  given Codec[String, BankAccountLinkId, CodecFormat.TextPlain]    = stringIdCodec(BankAccountLinkId.apply, _.value)
  given Codec[String, CardGroupId, CodecFormat.TextPlain]          = stringIdCodec(CardGroupId.apply, _.value)
}
