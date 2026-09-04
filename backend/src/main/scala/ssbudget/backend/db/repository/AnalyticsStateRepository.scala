package ssbudget.backend.db.repository

import cats.effect.IO
import doobie.*
import doobie.implicits.*
import ssbudget.backend.db.DoobieMeta.given

import java.time.Instant

/** The little that the app remembers about its Metabase instance.
  *
  * Both facts are "we already did this once" markers, and that is the whole point of them: the canonical dashboard and the curated table visibility
  * are the user's to change afterwards, so re-applying either on a later boot would quietly undo their work. Clearing a marker by hand is the
  * deliberate way to ask for it again.
  */
trait AnalyticsStateRepository {

  /** The Metabase id of the dashboard we seeded, if we ever did. */
  def canonicalDashboardId: IO[Option[Int]]
  def setCanonicalDashboardId(id: Int): IO[Unit]

  /** Whether sample content has been removed and the raw tables hidden. */
  def curated: IO[Boolean]
  def markCurated: IO[Unit]
}

class AnalyticsStateRepositoryImpl(xa: Transactor[IO]) extends AnalyticsStateRepository {

  import AnalyticsStateRepositoryImpl.*

  override def canonicalDashboardId: IO[Option[Int]] = get(CanonicalDashboardKey).map(_.flatMap(_.toIntOption))

  override def setCanonicalDashboardId(id: Int): IO[Unit] = put(CanonicalDashboardKey, id.toString)

  override def curated: IO[Boolean] = get(CuratedKey).map(_.isDefined)

  override def markCurated: IO[Unit] = put(CuratedKey, "1")

  private def get(key: String): IO[Option[String]] =
    sql"SELECT value FROM analytics_state WHERE key = $key".query[String].option.transact(xa)

  private def put(key: String, value: String): IO[Unit] =
    for {
      now <- IO.realTimeInstant
      _   <- sql"""
               INSERT INTO analytics_state (key, value, updated_at) VALUES ($key, $value, ${now: Instant})
               ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at
             """.update.run.transact(xa)
    } yield ()
}

object AnalyticsStateRepositoryImpl {
  private val CanonicalDashboardKey = "canonical-dashboard"
  private val CuratedKey            = "curated"
}
