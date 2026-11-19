package ssbudget.backend.db

import cats.implicits.*
import doobie.*
import doobie.implicits.*
import io.circe.parser.decode
import io.circe.syntax.*
import ssbudget.shared.model.*

import java.time.format.{DateTimeFormatter, DateTimeFormatterBuilder}
import java.time.{Instant, LocalDate}

object DoobieMeta {

  // ID types
  given Meta[AccountId]            = Meta[String].timap(AccountId.apply)(_.value)
  given Meta[PeriodId]             = Meta[String].timap(PeriodId.apply)(_.value)
  given Meta[BalanceSnapshotId]    = Meta[String].timap(BalanceSnapshotId.apply)(_.value)
  given Meta[BankConnectionId]     = Meta[String].timap(BankConnectionId.apply)(_.value)
  given Meta[BankAccountLinkId]    = Meta[String].timap(BankAccountLinkId.apply)(_.value)
  given Meta[CardGroupId]          = Meta[String].timap(CardGroupId.apply)(_.value)
  given Meta[BankTransactionId]    = Meta[String].timap(BankTransactionId.apply)(_.value)
  given Meta[CategoryId]           = Meta[String].timap(CategoryId.apply)(_.value)
  given Meta[ClassificationRuleId] = Meta[String].timap(ClassificationRuleId.apply)(_.value)
  given Meta[ImportJobId]          = Meta[String].timap(ImportJobId.apply)(_.value)

  // Value types
  given Meta[Currency] = Meta[String].timap(Currency.apply)(_.code)

  given Meta[ConnectionStatus] = Meta[String].tiemap(ConnectionStatus.fromString)(ConnectionStatus.asString)

  given Meta[AccountRole]          = Meta[String].tiemap(AccountRole.fromString)(AccountRole.asString)
  given Meta[BalanceSource]        = Meta[String].tiemap(BalanceSource.fromString)(BalanceSource.asString)
  given Meta[TransactionStatus]    = Meta[String].tiemap(TransactionStatus.fromString)(TransactionStatus.asString)
  given Meta[CategorySource]       = Meta[String].tiemap(CategorySource.fromString)(CategorySource.asString)
  given Meta[CategoryBudgetType]   = Meta[String].tiemap(CategoryBudgetType.fromString)(CategoryBudgetType.asString)
  given Meta[CategoryBudgetMethod] = Meta[String].tiemap(CategoryBudgetMethod.fromString)(CategoryBudgetMethod.asString)
  given Meta[ImportJobStatus]      = Meta[String].tiemap(ImportJobStatus.fromString)(ImportJobStatus.asString)
  given Meta[ImportJobKind]        = Meta[String].tiemap(ImportJobKind.fromString)(ImportJobKind.asString)

  // A rule's criteria list is stored as a single JSON column.
  given Meta[List[RuleCriterion]] =
    Meta[String].tiemap(s => decode[List[RuleCriterion]](s).leftMap(_.getMessage))(_.asJson.noSpaces)

  // BankLinkTarget spans two columns: (link_target_kind, link_target_id).
  given Read[BankLinkTarget] =
    Read[(String, Option[String])].map { case (kind, id) => BankLinkTarget.fromParts(kind, id) }

  given Write[BankLinkTarget] =
    Write[(String, Option[String])].contramap(t => (BankLinkTarget.kind(t), BankLinkTarget.idValue(t)))

  given Meta[SnapshotSource] = Meta[String].tiemap(SnapshotSource.fromString)(SnapshotSource.asString)

  // Date/Time types (stored as TEXT in SQLite)
  //
  // An instant has ONE text form: UTC with milliseconds, 'YYYY-MM-DDTHH:MM:SS.mmmZ', always 24 characters. The columns are compared as text
  // (`recorded_at <= ?`, `ORDER BY booked_at`), and only text of one width sorts as time does. `Instant.toString` prints 0, 3, 6 or 9 digits
  // of the second, so it must not be used here. Digits after the milliseconds are cut. V32 brought the rows of before to this form.
  private val instantText: DateTimeFormatter        = new DateTimeFormatterBuilder().appendInstant(3).toFormatter
  private[db] def instantToText(i: Instant): String = instantText.format(i)
  given Meta[Instant]                               = Meta[String].timap(Instant.parse)(instantToText)
  // Bare calendar days, 'YYYY-MM-DD' — SQLite's date() and strftime() read them directly.
  given Meta[LocalDate]                             = Meta[String].timap(LocalDate.parse)(_.toString)
}
