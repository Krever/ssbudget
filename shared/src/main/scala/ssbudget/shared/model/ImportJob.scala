package ssbudget.shared.model

import io.circe.Codec
import ssbudget.shared.json.{EnumCodec, StringId}

import java.time.Instant

final case class ImportJobId(value: String) extends AnyVal
object ImportJobId                          extends StringId[ImportJobId]

/** Lifecycle of a background import/sync run. `Failed` means the whole run blew up (fatal); per-account/connection problems in an otherwise-completed
  * run are surfaced on the items / `errors` (warnings), not as a job failure.
  */
enum ImportJobStatus {
  case Running, Succeeded, Failed
}

object ImportJobStatus {
  def asString(s: ImportJobStatus): String = s match {
    case Running   => "running"
    case Succeeded => "succeeded"
    case Failed    => "failed"
  }

  def fromString(s: String): Either[String, ImportJobStatus] = s match {
    case "running"   => Right(Running)
    case "succeeded" => Right(Succeeded)
    case "failed"    => Right(Failed)
    case other       => Left(s"Unknown import job status: $other")
  }

  given Codec[ImportJobStatus] = EnumCodec(values, asString, "import job status")
}

/** What triggered the run: a single connection's transaction import, or a "sync everything" across all connections. */
enum ImportJobKind {
  case Import, SyncAll
}

object ImportJobKind {
  def asString(k: ImportJobKind): String = k match {
    case Import  => "import"
    case SyncAll => "sync_all"
  }

  def fromString(s: String): Either[String, ImportJobKind] = s match {
    case "import"   => Right(Import)
    case "sync_all" => Right(SyncAll)
    case other      => Left(s"Unknown import job kind: $other")
  }

  given Codec[ImportJobKind] = EnumCodec(values, asString, "import job kind")
}

/** Per-account state within a job. `Pending` is reserved for future pre-enumeration / parallel fetch; today items appear as `Running` then settle to
  * `Done`/`Failed`.
  */
enum ImportItemStatus {
  case Pending, Running, Done, Failed
}

object ImportItemStatus {
  def asString(s: ImportItemStatus): String = s match {
    case Pending => "pending"
    case Running => "running"
    case Done    => "done"
    case Failed  => "failed"
  }

  def fromString(s: String): Either[String, ImportItemStatus] = s match {
    case "pending" => Right(Pending)
    case "running" => Right(Running)
    case "done"    => Right(Done)
    case "failed"  => Right(Failed)
    case other     => Left(s"Unknown import item status: $other")
  }

  given Codec[ImportItemStatus] = EnumCodec(values, asString, "import item status")
}

/** One account's slice of an import run — the drill-down unit. `imported`/`skipped` are this account's own transaction counts. */
final case class ImportJobItem(
    connection: String,
    account: String,
    status: ImportItemStatus,
    imported: Int,
    skipped: Int,
    error: Option[String],
) derives Codec.AsObject

/** A tracked background import/sync run. Persisted so the history + stats survive restarts.
  *
  *   - [[progress]]: current headline step while [[status]] is Running (e.g. "PKO — Checking"), cleared when finished.
  *   - [[imported]]/[[skipped]]: job totals (sum over [[items]]).
  *   - [[items]]: per-account breakdown (the second drill-down level), each with its own status + counts + error.
  *   - [[errors]]: connection-level problems that didn't abort the run (e.g. a balance-sync failure), shown as warnings.
  *   - [[message]]: fatal error when [[status]] is Failed.
  */
final case class ImportJob(
    id: ImportJobId,
    kind: ImportJobKind,
    label: String,
    status: ImportJobStatus,
    startedAt: Instant,
    finishedAt: Option[Instant],
    imported: Int,
    skipped: Int,
    progress: Option[String],
    items: List[ImportJobItem],
    errors: List[String],
    message: Option[String],
) derives Codec.AsObject
