package ssbudget.backend.db.repository

import cats.effect.IO
import cats.implicits.*
import doobie.*
import doobie.implicits.*
import io.circe.parser.decode
import io.circe.syntax.*
import ssbudget.backend.db.DoobieMeta.given
import ssbudget.shared.model.*

import java.time.Instant

trait ImportJobRepository {
  def create(job: ImportJob): IO[Unit]

  /** Persist a running job's live fields: progress step, running totals, per-account items and connection-level warnings. */
  def saveState(
      id: ImportJobId,
      progress: Option[String],
      imported: Int,
      skipped: Int,
      items: List[ImportJobItem],
      errors: List[String],
  ): IO[Unit]

  /** Flip a job to its terminal status (items/counts/errors keep their last saved values); clears progress. */
  def finish(id: ImportJobId, status: ImportJobStatus, message: Option[String], finishedAt: Instant): IO[Unit]

  def findById(id: ImportJobId): IO[Option[ImportJob]]

  /** Recent jobs, newest first, capped at `limit`. */
  def listRecent(limit: Int): IO[List[ImportJob]]

  /** Mark every still-Running job as Failed — called on boot to clean up runs interrupted by a restart. Returns how many were fixed. */
  def failRunning(finishedAt: Instant, message: String): IO[Int]
}

class ImportJobRepositoryImpl(xa: Transactor[IO]) extends ImportJobRepository {

  private val columns =
    fr"id, kind, label, status, started_at, finished_at, imported, skipped, progress, items, errors, message"

  // `items`/`errors` are JSON string columns (a bespoke `Meta[List[_]]` given would collide with `Meta[List[RuleCriterion]]`), so rows are read as a
  // tuple with those two as `String` and mapped here.
  private type Row = (
      ImportJobId,
      ImportJobKind,
      String,
      ImportJobStatus,
      Instant,
      Option[Instant],
      Int,
      Int,
      Option[String],
      String,
      String,
      Option[String],
  )

  private def toJob(r: Row): ImportJob =
    ImportJob(
      r._1,
      r._2,
      r._3,
      r._4,
      r._5,
      r._6,
      r._7,
      r._8,
      r._9,
      decode[List[ImportJobItem]](r._10).getOrElse(Nil),
      decode[List[String]](r._11).getOrElse(Nil),
      r._12,
    )

  override def create(job: ImportJob): IO[Unit] =
    sql"""INSERT INTO import_jobs (id, kind, label, status, started_at, finished_at, imported, skipped, progress, items, errors, message)
          VALUES (${job.id}, ${job.kind}, ${job.label}, ${job.status}, ${job.startedAt}, ${job.finishedAt},
                  ${job.imported}, ${job.skipped}, ${job.progress}, ${job.items.asJson.noSpaces}, ${job.errors.asJson.noSpaces},
                  ${job.message})""".update.run.transact(xa).void

  override def saveState(
      id: ImportJobId,
      progress: Option[String],
      imported: Int,
      skipped: Int,
      items: List[ImportJobItem],
      errors: List[String],
  ): IO[Unit] =
    sql"""UPDATE import_jobs
          SET progress = $progress, imported = $imported, skipped = $skipped, items = ${items.asJson.noSpaces}, errors = ${errors.asJson.noSpaces}
          WHERE id = $id""".update.run.transact(xa).void

  override def finish(id: ImportJobId, status: ImportJobStatus, message: Option[String], finishedAt: Instant): IO[Unit] =
    sql"UPDATE import_jobs SET status = $status, message = $message, finished_at = $finishedAt, progress = NULL WHERE id = $id".update.run
      .transact(xa)
      .void

  override def findById(id: ImportJobId): IO[Option[ImportJob]] =
    (fr"SELECT" ++ columns ++ fr"FROM import_jobs WHERE id = $id").query[Row].option.transact(xa).map(_.map(toJob))

  override def listRecent(limit: Int): IO[List[ImportJob]] =
    (fr"SELECT" ++ columns ++ fr"FROM import_jobs ORDER BY started_at DESC LIMIT $limit").query[Row].to[List].transact(xa).map(_.map(toJob))

  override def failRunning(finishedAt: Instant, message: String): IO[Int] =
    sql"""UPDATE import_jobs SET status = ${ImportJobStatus.Failed}, message = $message, finished_at = $finishedAt, progress = NULL
          WHERE status = ${ImportJobStatus.Running}""".update.run.transact(xa)
}
