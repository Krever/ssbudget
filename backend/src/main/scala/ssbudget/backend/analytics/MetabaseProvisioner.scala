package ssbudget.backend.analytics

import cats.data.EitherT
import cats.effect.IO
import cats.implicits.*
import io.circe.{Decoder, Json}
import io.circe.parser.parse
import io.circe.syntax.*
import ssbudget.backend.db.repository.AnalyticsStateRepository

import scala.concurrent.duration.*
import scala.util.Using

/** Brings a blank Metabase up to a working analytics instance, with no human steps.
  *
  * Metabase's declarative config file is a Pro feature, but everything it does is reachable through the REST API, which is not gated. So this walks
  * the same ground: create the first user, register the SQLite database, fix up field types, curate what is browsable, and seed one dashboard.
  *
  * Every step is idempotent and safe to run on every boot:
  *   - setup is guarded by `setup-token` going null once an instance has a user;
  *   - the database is matched by name;
  *   - curation and the dashboard are guarded by markers in *our* database, not Metabase's — see [[seedCanonicalDashboard]].
  */
class MetabaseProvisioner(config: MetabaseConfig, client: MetabaseClient, state: AnalyticsStateRepository) {

  import MetabaseProvisioner.*

  /** Runs the whole sequence until it succeeds, logging progress.
    *
    * It keeps retrying rather than giving up because "Metabase isn't ready yet" has no useful upper bound: its first boot migrates a fresh
    * application database, which on a small shared-CPU host takes many minutes — and longer still where the host suspends the machine between
    * requests, so the boot only advances while someone is looking. A single attempt that expires leaves the Analytics page saying "setting up" until
    * the next restart, which is the one outcome worth engineering against. Every step is idempotent, so a later attempt costs a few API calls and
    * picks up whatever the earlier ones already did.
    */
  def provision: IO[Unit] = {
    def attempt(delay: FiniteDuration): IO[Unit] =
      runOnce.flatMap {
        case Right(_)  => IO.unit
        // Provisioning failures must not take the app down: analytics is an optional extra, and a
        // half-configured Metabase is something the user can finish by hand.
        case Left(err) =>
          IO.println(s"Metabase provisioning incomplete, retrying in ${delay.toSeconds}s: $err") *>
            IO.sleep(delay) *> attempt(if delay * 2 > RetryCeiling then RetryCeiling else delay * 2)
      }
    attempt(RetryBase)
  }

  private def runOnce: IO[Either[String, Unit]] =
    (for {
      _    <- waitForHealth
      _    <- ensureSetup
      _    <- EitherT.liftF(warnOnPathMismatch)
      dbId <- ensureDatabase
      // The one metadata fetch every later step reads from. Neither coercions nor visibility changes
      // alter table or field ids, so this snapshot stays valid for all of them.
      meta <- waitForSync(dbId)
      _    <- EitherT.liftF(applyCoercions(meta))
      _    <- curateOnce(meta)
      _    <- seedCanonicalDashboard(meta)
    } yield ()).value

  /** Metabase takes tens of seconds to boot; poll until it says it's ready. */
  private def waitForHealth: EitherT[IO, String, Unit] = {
    def attempt(remaining: Int): IO[Either[String, Unit]] =
      client.getPublic(Seq("api", "health")).flatMap {
        case Right(j) if j.hcursor.get[String]("status").toOption.contains("ok") => IO.pure(Right(()))
        case _ if remaining > 0                                                  => IO.sleep(PollInterval) *> attempt(remaining - 1)
        case other                                                               => IO.pure(Left(s"Metabase did not become healthy: $other"))
      }
    EitherT(attempt(HealthAttempts))
  }

  /** Creates the first (admin) user, unless the instance already has one.
    *
    * `setup-token` is non-null only on a virgin instance, so its absence is the idempotency guard — no extra bookkeeping needed. Note we deliberately
    * do not pre-set `MB_SETUP_TOKEN`: doing so is known to leave the frontend stuck in setup mode.
    */
  private def ensureSetup: EitherT[IO, String, Unit] =
    EitherT(client.getPublic(Seq("api", "session", "properties"))).flatMap { props =>
      props.hcursor.get[Option[String]]("setup-token").toOption.flatten match {
        case None        => EitherT.liftF(IO.println("Metabase: already set up"))
        case Some(token) =>
          val body = Json.obj(
            "token"    -> token.asJson,
            "user"     -> Json.obj(
              "first_name" -> "SSBudget".asJson,
              "last_name"  -> "Service".asJson,
              "email"      -> config.user.asJson,
              "password"   -> config.password.asJson,
              "site_name"  -> SiteName.asJson,
            ),
            "prefs"    -> Json.obj(
              "site_name"      -> SiteName.asJson,
              "site_locale"    -> "en".asJson,
              "allow_tracking" -> false.asJson,
            ),
            "database" -> Json.Null,
          )
          EitherT(client.postPublic(Seq("api", "setup"), body)).void <* EitherT.liftF(IO.println("Metabase: created service account"))
      }
    }

  /** Metabase derives the `<base href>` for its assets from `MB_SITE_URL`, so that setting's path must equal the prefix we proxy it under. A mismatch
    * renders a blank iframe and reports nothing anywhere, which is a miserable thing to debug — so say it plainly at boot.
    */
  private def warnOnPathMismatch: IO[Unit] =
    client.getPublic(Seq("api", "session", "properties")).flatMap {
      case Left(_)      => IO.unit
      case Right(props) =>
        val sitePath = props.hcursor.get[String]("site-url").toOption.map { url =>
          val afterScheme = url.dropWhile(_ != '/').dropWhile(_ == '/')
          val path        = afterScheme.dropWhile(_ != '/')
          if path.isEmpty then "/" else path
        }
        sitePath.filter(_.stripSuffix("/") != config.path.stripSuffix("/")) match {
          case Some(p) =>
            IO.println(
              s"Metabase: MB_SITE_URL path is '$p' but the app proxies Metabase under '${config.path}' — " +
                s"its assets will not load. Set MB_SITE_URL to end with '${config.path}'.",
            )
          case None    => IO.unit
        }
    }

  /** Registers the app's SQLite file as a data source, matched by name so a restart doesn't duplicate it. */
  private def ensureDatabase: EitherT[IO, String, Int] =
    EitherT(client.get(Seq("api", "database"))).flatMap { json =>
      val existing = json.hcursor
        .downField("data")
        .values
        .getOrElse(Nil)
        .find(_.hcursor.get[String]("name").toOption.contains(DatabaseName))
        .flatMap(_.hcursor.get[Int]("id").toOption)
      existing match {
        case Some(id) => EitherT.liftF(IO.println(s"Metabase: database '$DatabaseName' already registered (id $id)").as(id))
        case None     =>
          val body = Json.obj(
            "engine"       -> "sqlite".asJson,
            "name"         -> DatabaseName.asJson,
            // `open_mode=1` opens the file read-only: Metabase can explore the app's data but never write to it.
            "details"      -> Json.obj(
              "db"                 -> config.dataDbPath.asJson,
              "advanced-options"   -> true.asJson,
              "additional-options" -> "open_mode=1".asJson,
            ),
            "is_full_sync" -> true.asJson,
          )
          for {
            created <- EitherT(client.post(Seq("api", "database"), body))
            id      <- EitherT.fromOption[IO](created.hcursor.get[Int]("id").toOption, "Metabase did not return a database id")
            _       <- EitherT.liftF(IO.println(s"Metabase: registered database '$DatabaseName' (id $id)"))
          } yield id
      }
    }

  /** Waits for Metabase to finish syncing, and hands back the metadata it fetched so no later step has to ask again.
    *
    * "Finished" has to mean `initial_sync_status: complete`, not merely "tables have appeared". Metabase syncs in two passes: the schema shows up
    * first, then a second pass fingerprints the values and settles each field's type. Seeding between the two passes is silently ruinous — cards
    * record the source table's raw columns as their result shape instead of the query's, so every chart renders as "which fields do you want to use
    * for the X and Y axes?", and the date coercions are skipped because the field isn't typed as text yet. It looks fine on a fast machine, where the
    * two passes are seconds apart, and breaks on a small host where they are minutes apart.
    */
  private def waitForSync(dbId: Int): EitherT[IO, String, DbMetadata] = {
    def attempt(remaining: Int): IO[Either[String, DbMetadata]] =
      fetchMetadata(dbId).flatMap {
        case Right(meta) if meta.tables.nonEmpty && meta.tables.forall(_.synced) => IO.pure(Right(meta))
        case _ if remaining > 0                                                  => IO.sleep(PollInterval) *> attempt(remaining - 1)
        case other                                                               => IO.pure(Left(s"Metabase never finished syncing: $other"))
      }
    EitherT(attempt(SyncAttempts))
  }

  private def fetchMetadata(dbId: Int): IO[Either[String, DbMetadata]] =
    client.get(Seq("api", "database", dbId.toString, "metadata")).map(_.map(DbMetadata.from(dbId, _)))

  /** Teaches Metabase which text columns are really dates.
    *
    * SQLite views carry no declared column types, so Metabase infers them from sampled values and lands on `type/Text` for our ISO-8601 dates. Left
    * alone that disables time-series grouping and date filters — most of the value, since the dashboard's date filter needs a real date column.
    *
    * Best-effort by design, for two reasons. On a brand-new deployment the tables are still empty, so Metabase infers types from nothing and rejects
    * the coercion as incompatible; and a user is free to have changed a field's type by hand. Neither is a reason to abandon provisioning — and since
    * this runs on every boot, the first start that happens to have data fixes up whatever the empty one got wrong.
    */
  private def applyCoercions(meta: DbMetadata): IO[Unit] =
    spec.fold(
      err => IO.println(s"Metabase: cannot read the dashboard spec, skipping coercions: $err"),
      _.coercions.traverse_ { c =>
        meta.field(c.table, c.field) match {
          // Missing (an older database, or a view we no longer ship), or already typed as something
          // other than plain text — either way, not ours to override.
          case Some(f) if f.baseType.contains("type/Text") =>
            client.put(Seq("api", "field", f.id.toString), Json.obj("coercion_strategy" -> c.strategy.asJson)).flatMap {
              case Right(_) => IO.unit
              case Left(e)  => IO.println(s"Metabase: could not coerce ${c.table}.${c.field} (harmless, retried next boot): ${e.take(160)}")
            }
          case _                                           => IO.unit
        }
      },
    )

  /** Trims Metabase down to what is worth exploring — once, then never again.
    *
    * A fresh Metabase presents its own sample database and an "Examples" collection, and our database shows all its tables. That is a poor starting
    * point: the interesting surface is the `v_*` views, and the raw tables include ones nobody should be browsing at all — `sessions` holds live
    * session tokens, `auth_config` a password hash, `passkey_credentials` public keys.
    *
    * Hidden tables stay reachable from SQL for anyone who really wants them; they just leave the query builder's picker. This is presentation, not a
    * security boundary — the service account is an admin either way.
    *
    * One-time, guarded like the dashboard: a later boot must not re-hide a table the user deliberately un-hid. Clear the marker to run it again, e.g.
    * after a migration adds tables.
    */
  private def curateOnce(meta: DbMetadata): EitherT[IO, String, Unit] =
    EitherT.liftF(state.curated).flatMap {
      case true  => EitherT.pure(())
      case false => EitherT.liftF(removeSampleContent *> hideNonReportingTables(meta) *> state.markCurated)
    }

  /** Drops Metabase's own sample database and example collection. New instances start with `MB_LOAD_SAMPLE_CONTENT=false` so these never appear; this
    * cleans up instances created before that, and is a no-op when there is nothing to remove.
    */
  private def removeSampleContent: IO[Unit] = {
    def purge(listing: IO[Either[String, Json]], at: Json => Option[Iterable[Json]], what: String)(
        remove: Int => IO[Either[String, Json]],
    ): IO[Unit] =
      listing.flatMap {
        case Left(_)     => IO.unit
        case Right(json) =>
          sampleIds(at(json)).traverse_ { id =>
            remove(id).flatMap {
              case Right(_) => IO.println(s"Metabase: removed sample $what (id $id)")
              case Left(e)  => IO.println(s"Metabase: could not remove sample $what: ${e.take(120)}")
            }
          }
      }

    purge(client.get(Seq("api", "database")), _.hcursor.downField("data").values, "database")(id =>
      client.delete(Seq("api", "database", id.toString)),
    ) *>
      purge(client.get(Seq("api", "collection")), _.hcursor.values, "collection")(id =>
        client.put(Seq("api", "collection", id.toString), Json.obj("archived" -> true.asJson)),
      )
  }

  /** Ids of entries Metabase itself flags as sample content. Collection ids can be the string "root", hence the int filter. */
  private def sampleIds(values: Option[Iterable[Json]]): List[Int] =
    values.getOrElse(Nil).toList.filter(_.hcursor.get[Boolean]("is_sample").toOption.contains(true)).flatMap(_.hcursor.get[Int]("id").toOption)

  /** Hides everything that isn't one of our reporting views, so the query builder offers a curated set. */
  private def hideNonReportingTables(meta: DbMetadata): IO[Unit] = {
    val raw = meta.tables.filterNot(_.name.startsWith(ReportingViewPrefix))
    raw.traverse_(t => client.put(Seq("api", "table", t.id.toString), Json.obj("visibility_type" -> "hidden".asJson)).void) *>
      IO.println(s"Metabase: hid ${raw.size} raw tables, leaving the $ReportingViewPrefix* views")
  }

  /** Creates the dashboard that ships with the app — exactly once, ever.
    *
    * The guard lives in our own database rather than in Metabase's state, and that is the point: the dashboard is the user's to rename, rearrange or
    * delete, and re-applying our JSON on a later boot would silently destroy that work. If they delete it, it stays deleted.
    */
  private def seedCanonicalDashboard(meta: DbMetadata): EitherT[IO, String, Unit] =
    EitherT.liftF(state.canonicalDashboardId).flatMap {
      case Some(id) => EitherT.liftF(IO.println(s"Metabase: canonical dashboard already seeded (id $id)"))
      case None     =>
        for {
          loaded  <- EitherT.fromEither[IO](spec)
          ids      = meta.placeholders
          cardIds <- loaded.cards.traverse(c => EitherT(createCard(c, ids)))
          dashId  <- createDashboard(loaded, loaded.cards.zip(cardIds), ids)
          _       <- EitherT.liftF(state.setCanonicalDashboardId(dashId))
          _       <- EitherT.liftF(IO.println(s"Metabase: seeded canonical dashboard (id $dashId)"))
        } yield ()
    }

  private def createCard(card: CardSpec, ids: Map[String, Json]): IO[Either[String, Int]] = {
    val body = Json.obj(
      "name"                   -> card.name.asJson,
      "display"                -> card.display.asJson,
      "visualization_settings" -> card.visualizationSettings,
      "dataset_query"          -> resolve(card.datasetQuery, ids),
    )
    client
      .post(Seq("api", "card"), body)
      .map(_.flatMap { j =>
        j.hcursor.get[Int]("id").toOption.toRight(s"Metabase did not return an id for card '${card.name}'")
      })
  }

  private def createDashboard(spec: DashboardSpec, cards: List[(CardSpec, Int)], ids: Map[String, Json]): EitherT[IO, String, Int] = {
    // Dashboard-level filter widgets. Each needs a stable id that the per-card mappings refer to.
    val parameters = spec.filters.map { f =>
      Json
        .obj(
          "id"        -> parameterId(f.slug).asJson,
          "name"      -> f.name.asJson,
          "slug"      -> f.slug.asJson,
          "type"      -> f.`type`.asJson,
          "sectionId" -> f.sectionId.asJson,
        )
        .deepMerge(f.default.fold(Json.obj())(d => Json.obj("default" -> d.asJson)))
    }

    // Every card here reads from the same table, so each filter maps onto every card by the field it names.
    def mappingsFor(cardId: Int): List[Json] =
      spec.filters.flatMap { f =>
        ids.get(f.field).flatMap(_.asNumber).map { fieldId =>
          Json.obj(
            "parameter_id" -> parameterId(f.slug).asJson,
            "card_id"      -> cardId.asJson,
            "target"       -> Json.arr("dimension".asJson, Json.arr("field".asJson, Json.fromJsonNumber(fieldId), Json.Null)),
          )
        }
      }

    // Static embeds ignore filters unless each is explicitly published as "enabled", which is what
    // makes the widget usable inside the iframe rather than merely present.
    val embeddingParams = Json.fromFields(spec.filters.map(f => f.slug -> "enabled".asJson))

    for {
      created  <- EitherT(
                    client.post(
                      Seq("api", "dashboard"),
                      Json.obj("name" -> spec.name.asJson, "description" -> spec.description.asJson, "parameters" -> parameters.asJson),
                    ),
                  )
      dashId   <- EitherT.fromOption[IO](created.hcursor.get[Int]("id").toOption, "Metabase did not return a dashboard id")
      // Dashcards are placed in a follow-up PUT; new ones are identified by negative placeholder ids.
      dashcards = cards.zipWithIndex.map { case ((card, cardId), i) =>
                    Json.obj(
                      "id"                     -> (-(i + 1)).asJson,
                      "card_id"                -> cardId.asJson,
                      "row"                    -> card.layout.row.asJson,
                      "col"                    -> card.layout.col.asJson,
                      "size_x"                 -> card.layout.sizeX.asJson,
                      "size_y"                 -> card.layout.sizeY.asJson,
                      "parameter_mappings"     -> mappingsFor(cardId).asJson,
                      "visualization_settings" -> Json.obj(),
                    )
                  }
      _        <- EitherT(client.put(Seq("api", "dashboard", dashId.toString), Json.obj("dashcards" -> dashcards.asJson)))
      // Static embedding is per-resource: without this the signed JWT is rejected.
      _        <- EitherT(
                    client.put(
                      Seq("api", "dashboard", dashId.toString),
                      Json.obj("enable_embedding" -> true.asJson, "embedding_params" -> embeddingParams),
                    ),
                  )
    } yield dashId
  }

  /** Walks a JSON tree replacing placeholder strings with the ids they name. */
  private def resolve(json: Json, ids: Map[String, Json]): Json =
    json.fold(
      Json.Null,
      Json.fromBoolean,
      Json.fromJsonNumber,
      str => ids.getOrElse(str, Json.fromString(str)),
      arr => Json.fromValues(arr.map(resolve(_, ids))),
      obj => Json.fromFields(obj.toIterable.map { case (k, v) => k -> resolve(v, ids) }),
    )

  /** The shipped spec, read from the jar once. */
  private lazy val spec: Either[String, DashboardSpec] = DashboardSpec.load
}

object MetabaseProvisioner {

  private val SiteName       = "SSBudget"
  private val DatabaseName   = "SSBudget"
  private val PollInterval   = 2.seconds
  private val HealthAttempts = 90  // ~3 minutes per attempt; the outer retry covers a slower boot
  private val SyncAttempts   = 150 // ~5 minutes; the analysis pass is slow on a small host, and the outer retry covers worse
  private val RetryBase      = 30.seconds
  private val RetryCeiling   = 5.minutes

  /** Views with this prefix are the exploration surface; everything else is hidden from the query builder. */
  private val ReportingViewPrefix = "v_"

  // --- Metabase's view of our database ----------------------------------------------------------

  final case class FieldMeta(id: Int, name: String, baseType: Option[String])
  final case class TableMeta(id: Int, name: String, fields: List[FieldMeta], initialSyncStatus: Option[String]) {

    /** Absent on Metabase versions that predate the field: assume it finished rather than wait forever. */
    def synced: Boolean = initialSyncStatus.forall(_ == "complete")
  }

  /** One parse of `GET /api/database/:id/metadata`, serving every step that needs table or field ids. */
  final case class DbMetadata(dbId: Int, tables: List[TableMeta]) {

    def field(table: String, field: String): Option[FieldMeta] =
      tables.find(_.name == table).flatMap(_.fields.find(_.name == field))

    /** The `{{db}}` / `{{table:x}}` / `{{field:x.y}}` lookup the shipped dashboard spec is written against. */
    def placeholders: Map[String, Json] =
      tables.foldLeft(Map("{{db}}" -> dbId.asJson)) { (acc, t) =>
        acc + (s"{{table:${t.name}}}" -> t.id.asJson) ++ t.fields.map(f => s"{{field:${t.name}.${f.name}}}" -> f.id.asJson)
      }
  }

  object DbMetadata {
    private given Decoder[FieldMeta] = Decoder.forProduct3("id", "name", "base_type")(FieldMeta.apply)
    private given Decoder[TableMeta] = Decoder.forProduct4("id", "name", "fields", "initial_sync_status")(TableMeta.apply)

    /** Lenient on purpose: a table Metabase describes in a shape we don't expect should cost us that table, not the whole provisioning run. */
    def from(dbId: Int, json: Json): DbMetadata =
      DbMetadata(dbId, json.hcursor.downField("tables").values.getOrElse(Nil).toList.flatMap(_.as[TableMeta].toOption))
  }

  // --- the shipped dashboard spec ---------------------------------------------------------------

  final case class Layout(row: Int, col: Int, sizeX: Int, sizeY: Int)
  final case class CardSpec(name: String, display: String, visualizationSettings: Json, datasetQuery: Json, layout: Layout)
  final case class Coercion(table: String, field: String, strategy: String)

  /** A dashboard-level filter widget. `field` is a `{{field:table.column}}` placeholder naming the column every card is filtered on. */
  final case class FilterSpec(name: String, slug: String, `type`: String, sectionId: String, default: Option[String], field: String)

  final case class DashboardSpec(
      name: String,
      description: String,
      coercions: List[Coercion],
      filters: List[FilterSpec],
      cards: List[CardSpec],
  )

  /** Metabase identifies dashboard parameters by an opaque 8-hex-character id. Deriving it from the slug keeps it stable and readable in logs. */
  private def parameterId(slug: String): String =
    String.format("%08x", Integer.valueOf(slug.hashCode & 0x7fffffff))

  object DashboardSpec {
    private val ResourcePath = "/analytics/canonical-dashboard.json"

    // Keys mirror Metabase's own API vocabulary (`dataset_query`, `size_x`) so the spec reads alongside
    // Metabase's docs — hence explicit key names rather than derived camelCase codecs.
    private given Decoder[Layout]        = Decoder.forProduct4("row", "col", "size_x", "size_y")(Layout.apply)
    private given Decoder[Coercion]      = Decoder.forProduct3("table", "field", "strategy")(Coercion.apply)
    private given Decoder[FilterSpec]    = Decoder.instance { c =>
      for {
        name    <- c.get[String]("name")
        slug    <- c.get[String]("slug")
        typ     <- c.get[String]("type")
        section <- c.getOrElse[String]("sectionId")("date")
        default <- c.get[Option[String]]("default")
        field   <- c.get[String]("field")
      } yield FilterSpec(name, slug, typ, section, default, field)
    }
    private given Decoder[CardSpec]      = Decoder.instance { c =>
      for {
        name    <- c.get[String]("name")
        display <- c.get[String]("display")
        viz     <- c.getOrElse[Json]("visualization_settings")(Json.obj())
        query   <- c.get[Json]("dataset_query")
        layout  <- c.get[Layout]("layout")
      } yield CardSpec(name, display, viz, query, layout)
    }
    private given Decoder[DashboardSpec] = Decoder.instance { c =>
      for {
        name        <- c.get[String]("name")
        description <- c.get[String]("description")
        coercions   <- c.getOrElse[List[Coercion]]("coercions")(Nil)
        filters     <- c.getOrElse[List[FilterSpec]]("filters")(Nil)
        cards       <- c.get[List[CardSpec]]("cards")
      } yield DashboardSpec(name, description, coercions, filters, cards)
    }

    /** Reads the shipped dashboard spec from the jar. A malformed spec is reported, not silently trimmed. */
    def load: Either[String, DashboardSpec] =
      for {
        raw  <- Using(getClass.getResourceAsStream(ResourcePath))(s => new String(s.readAllBytes(), "UTF-8")).toEither.left
                  .map(e => s"Cannot read $ResourcePath: ${e.getMessage}")
        json <- parse(raw).left.map(e => s"$ResourcePath is not valid JSON: ${e.getMessage}")
        spec <- json.as[DashboardSpec].left.map(e => s"$ResourcePath does not match the expected shape: ${e.getMessage}")
      } yield spec
  }
}
