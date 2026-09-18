package ssbudget.backend.db.repository

import cats.effect.IO
import doobie.*
import doobie.implicits.*
import ssbudget.backend.db.DoobieMeta.given

import java.time.Instant

/** The little that the app remembers about its Metabase instance.
  *
  * Most of it is "we already did this once" bookkeeping, and that is the point: the canonical dashboard and the curated table visibility are the
  * user's to change afterwards, so blindly re-applying either on a later boot would quietly undo their work. Clearing a marker by hand is the
  * deliberate way to ask for it again.
  *
  * The dashboard is the exception, because it also has to be able to GAIN cards the app ships later. That needs three more facts: which version of
  * the shipped spec was last applied, the Metabase revision the dashboard was left at (its own record of whether anyone has edited it since), and
  * which card each spec entry became, so an update can tell "this card is missing" from "this card was renamed".
  */
trait AnalyticsStateRepository {

  /** The Metabase id of the dashboard we seeded, if we ever did. */
  def canonicalDashboardId: IO[Option[Int]]
  def setCanonicalDashboardId(id: Int): IO[Unit]

  /** The version of the shipped spec last applied to that dashboard; absent for one seeded before versioning existed. */
  def canonicalDashboardVersion: IO[Option[Int]]
  def setCanonicalDashboardVersion(version: Int): IO[Unit]

  /** The dashboard's latest Metabase revision id at the moment we last wrote it. A different one today means the user has edited it since. */
  def canonicalDashboardRevision: IO[Option[Int]]
  def setCanonicalDashboardRevision(revision: Int): IO[Unit]

  /** The spec card keys the dashboard already carries — what an update diffs against to find the cards it is missing. */
  def canonicalDashboardCards: IO[Set[String]]
  def setCanonicalDashboardCards(keys: Set[String]): IO[Unit]

  /** Whether sample content has been removed and the raw tables hidden. */
  def curated: IO[Boolean]
  def markCurated: IO[Unit]
}

class AnalyticsStateRepositoryImpl(xa: Transactor[IO]) extends AnalyticsStateRepository {

  import AnalyticsStateRepositoryImpl.*

  override def canonicalDashboardId: IO[Option[Int]] = get(CanonicalDashboardKey).map(_.flatMap(_.toIntOption))

  override def setCanonicalDashboardId(id: Int): IO[Unit] = put(CanonicalDashboardKey, id.toString)

  override def canonicalDashboardVersion: IO[Option[Int]] = get(VersionKey).map(_.flatMap(_.toIntOption))

  override def setCanonicalDashboardVersion(version: Int): IO[Unit] = put(VersionKey, version.toString)

  override def canonicalDashboardRevision: IO[Option[Int]] = get(RevisionKey).map(_.flatMap(_.toIntOption))

  override def setCanonicalDashboardRevision(revision: Int): IO[Unit] = put(RevisionKey, revision.toString)

  /** A comma-separated list of keys: a dozen short identifiers, kept legible in the table rather than JSON-encoded. */
  override def canonicalDashboardCards: IO[Set[String]] =
    get(CardsKey).map(_.fold(Set.empty[String])(_.split(',').filter(_.nonEmpty).toSet))

  override def setCanonicalDashboardCards(keys: Set[String]): IO[Unit] =
    put(CardsKey, keys.toList.sorted.mkString(","))

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
  private val VersionKey            = "canonical-dashboard-version"
  private val RevisionKey           = "canonical-dashboard-revision"
  private val CardsKey              = "canonical-dashboard-cards"
  private val CuratedKey            = "curated"
}
