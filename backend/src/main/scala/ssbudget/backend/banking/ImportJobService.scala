package ssbudget.backend.banking

import cats.effect.{IO, Ref}
import cats.effect.std.Supervisor
import cats.implicits.*
import ssbudget.backend.db.Repositories
import ssbudget.shared.api.ImportTransactionsRequest
import ssbudget.shared.model.*

import java.util.UUID

/** Runs transaction imports / full syncs as tracked background jobs, so the HTTP request returns a job record immediately and the UI polls for
  * progress. The work runs on an app-scoped [[Supervisor]] (created at startup), so it outlives the request.
  *
  * A job carries a **per-account breakdown** ([[ImportJob.items]]): each account appears as it starts (Running) and settles to Done with its own
  * imported/skipped counts (the second UI drill-down level, and the seam for future parallel fetching). Connection-level problems (e.g. a
  * balance-sync failure, or an import that never got going) become warnings on the job; only an unexpected exception marks the whole job Failed. A
  * new run is refused while one is still Running (returns the running job) so double-clicks don't double-import.
  */
class ImportJobService(
    repos: Repositories,
    supervisor: Supervisor[IO],
    bankingService: BankingService,
    importService: TransactionImportService,
) {

  /** Accumulates per-account items + connection warnings for one job, persisting on every change so the polling UI sees items/counts fill in. */
  final private class Reporter(
      jobId: ImportJobId,
      items: Ref[IO, List[ImportJobItem]],
      errors: Ref[IO, List[String]],
      progress: Ref[IO, Option[String]],
  ) {
    private def persist: IO[Unit] =
      for {
        its  <- items.get
        errs <- errors.get
        prog <- progress.get
        _    <- repos.importJobs.saveState(jobId, prog, its.map(_.imported).sum, its.map(_.skipped).sum, its, errs)
      } yield ()

    private def upsert(connection: String, account: String)(f: ImportJobItem => ImportJobItem): IO[Unit] =
      items.update { list =>
        if list.exists(i => i.connection == connection && i.account == account) then list.map(i =>
          if i.connection == connection && i.account == account then f(i) else i,
        )
        else list :+ f(ImportJobItem(connection, account, ImportItemStatus.Running, 0, 0, None))
      }

    def itemRunning(connection: String, account: String): IO[Unit] =
      progress.set(Some(s"$connection — $account")) *> upsert(connection, account)(_.copy(status = ImportItemStatus.Running)) *> persist

    def itemDone(connection: String, account: String, imported: Int, skipped: Int): IO[Unit] =
      progress.set(Some(s"$connection — $account")) *>
        upsert(connection, account)(_.copy(status = ImportItemStatus.Done, imported = imported, skipped = skipped, error = None)) *> persist

    /** A connection-level problem (balance sync, or an import that failed before/after per-account work): record a warning and mark any of that
      * connection's still-running items as Failed.
      */
    def failConnection(connection: String, error: String): IO[Unit] =
      items.update(
        _.map(i =>
          if i.connection == connection && i.status == ImportItemStatus.Running then i.copy(status = ImportItemStatus.Failed, error = Some(error))
          else i,
        ),
      ) *>
        warn(connection, error)

    def warn(connection: String, message: String): IO[Unit] =
      errors.update(_ :+ s"$connection: $message") *> persist
  }

  def listRecent: IO[List[ImportJob]]             = repos.importJobs.listRecent(50)
  def get(id: ImportJobId): IO[Option[ImportJob]] = repos.importJobs.findById(id)

  /** Import one connection's transactions in the background. */
  def startImport(connectionId: BankConnectionId, req: ImportTransactionsRequest): IO[ImportJob] =
    connectionLabel(connectionId).flatMap { bank =>
      start(ImportJobKind.Import, s"Import — $bank")(runImport(connectionId, req, bank, _))
    }

  /** Sync balances + import transactions across all connections in the background. */
  def startSyncAll(): IO[ImportJob] =
    start(ImportJobKind.SyncAll, "Sync all banks")(runSyncAll)

  // ---- internals ----

  private def connectionLabel(id: BankConnectionId): IO[String] =
    repos.bankConnections.findById(id).map(_.map(_.aspspName).getOrElse(id.value.take(8)))

  /** Create a Running job, supervise `work` in the background, and return the created job immediately. Refuses to start if a run is already in
    * progress (returns that job). `work` reports progress/items via the [[Reporter]]; any raised exception fails the job.
    */
  private def start(kind: ImportJobKind, label: String)(work: Reporter => IO[Unit]): IO[ImportJob] =
    repos.importJobs.listRecent(1).flatMap {
      case existing :: _ if existing.status == ImportJobStatus.Running => IO.pure(existing)
      case _                                                           =>
        for {
          now <- IO.realTimeInstant
          job  = ImportJob(
                   ImportJobId(UUID.randomUUID().toString),
                   kind,
                   label,
                   ImportJobStatus.Running,
                   now,
                   None,
                   0,
                   0,
                   Some("starting…"),
                   Nil,
                   Nil,
                   None,
                 )
          _   <- repos.importJobs.create(job)
          _   <- supervisor.supervise(
                   for {
                     items    <- Ref.of[IO, List[ImportJobItem]](Nil)
                     errors   <- Ref.of[IO, List[String]](Nil)
                     progress <- Ref.of[IO, Option[String]](Some("starting…"))
                     outcome  <- work(new Reporter(job.id, items, errors, progress)).attempt
                     fin      <- IO.realTimeInstant
                     _        <- outcome match {
                                   case Right(_) => repos.importJobs.finish(job.id, ImportJobStatus.Succeeded, None, fin)
                                   case Left(e)  =>
                                     repos.importJobs.finish(job.id, ImportJobStatus.Failed, Some(Option(e.getMessage).getOrElse(e.toString)), fin)
                                 }
                   } yield (),
                 )
        } yield job
    }

  private def runImport(connectionId: BankConnectionId, req: ImportTransactionsRequest, bank: String, reporter: Reporter): IO[Unit] =
    importService
      .importTransactions(
        connectionId,
        req,
        onAccountStart = acct => reporter.itemRunning(bank, acct),
        onAccountDone = (acct, imp, skp) => reporter.itemDone(bank, acct, imp, skp),
      )
      .flatMap {
        case Right(_)  => IO.unit
        case Left(err) => IO.raiseError(new RuntimeException(err)) // a single-connection import failing fails the job
      }

  private def runSyncAll(reporter: Reporter): IO[Unit] =
    for {
      conns <- repos.bankConnections.findAll
      active = conns.filter(_.sessionId.isDefined) // skip connections still pending authorization
      _     <- active.traverse_ { conn =>
                 val bank = conn.aspspName
                 // Sync + import are attempted independently so one failing doesn't suppress the other; problems become warnings.
                 for {
                   syncE <- bankingService.sync(conn.id).handleError(t => Left(t.getMessage))
                   _     <- syncE match {
                              case Left(e)        => reporter.warn(bank, s"balance sync — $e")
                              case Right(outcome) => outcome.warnings.traverse_(w => reporter.warn(bank, s"balance — $w"))
                            }
                   impE  <- importService
                              .importTransactions(
                                conn.id,
                                ImportTransactionsRequest(None),
                                onAccountStart = acct => reporter.itemRunning(bank, acct),
                                onAccountDone = (acct, imp, skp) => reporter.itemDone(bank, acct, imp, skp),
                              )
                              .handleError(t => Left(t.getMessage))
                   _     <- impE.left.toOption.traverse_(e => reporter.failConnection(bank, s"import — $e"))
                 } yield ()
               }
    } yield ()
}
