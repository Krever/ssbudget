package ssbudget.backend.analytics

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import io.circe.Json
import io.circe.parser.parse
import org.scalatest.BeforeAndAfterEach
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import ssbudget.backend.db.repository.AnalyticsStateRepository
import sttp.client3.httpclient.fs2.HttpClientFs2Backend

import scala.jdk.CollectionConverters.*

/** How a dashboard the app already seeded receives a version the app ships later.
  *
  * The rule being protected: the user's arrangement wins. A dashboard nobody has touched may be replaced outright; one that has been edited only ever
  * gains cards, appended below what is already there, with every existing dashcard written back exactly as it came.
  */
class DashboardReconcileSpec extends AnyFreeSpec with Matchers with BeforeAndAfterEach {

  private var wm: WireMockServer = scala.compiletime.uninitialized

  override def beforeEach(): Unit = {
    wm = new WireMockServer(options().dynamicPort())
    wm.start()
    configureFor("localhost", wm.port())
    stubCommon()
  }

  override def afterEach(): Unit = wm.stop()

  /** The handful of endpoints every run touches, regardless of which path it takes. */
  private def stubCommon(): Unit = {
    stubFor(post(urlEqualTo("/api/session")).willReturn(okJson("""{"id":"session-token"}""")))
    stubFor(post(urlEqualTo("/api/card")).willReturn(okJson("""{"id":999}""")))
    stubFor(put(urlMatching("/api/dashboard/7")).willReturn(okJson("{}")))
    stubFor(post(urlEqualTo("/api/dashboard/7/copy")).willReturn(okJson("""{"id":8}""")))
    stubFor(get(urlPathEqualTo("/api/revision")).willReturn(okJson("""[{"id":42}]""")))
  }

  /** A live dashboard carrying one card the user has since rearranged. */
  private def liveDashboard(extraCards: Json*): Unit = {
    val existing = Json.obj(
      "id"     -> Json.fromInt(100),
      "row"    -> Json.fromInt(4),
      "col"    -> Json.fromInt(0),
      "size_x" -> Json.fromInt(24),
      "size_y" -> Json.fromInt(6),
      "card"   -> Json.obj("name" -> Json.fromString("Spent")),
    )
    val body     = Json.obj(
      "id"        -> Json.fromInt(7),
      "archived"  -> Json.fromBoolean(false),
      "dashcards" -> Json.arr((existing +: extraCards)*),
    )
    stubFor(get(urlEqualTo("/api/dashboard/7")).willReturn(okJson(body.noSpaces)))
  }

  private def config = MetabaseConfig(s"http://localhost:${wm.port()}", "svc@test", "pw", "secret", "/metabase", "/tmp/test.db")

  /** An in-memory stand-in for the real repository — this spec is about the decision, not about SQLite. */
  private class FakeState(
      var dashboardId: Option[Int],
      var version: Option[Int],
      var revision: Option[Int],
      var cards: Set[String],
  ) extends AnalyticsStateRepository {
    def canonicalDashboardId: IO[Option[Int]]                = IO.pure(dashboardId)
    def setCanonicalDashboardId(id: Int): IO[Unit]           = IO { dashboardId = Some(id) }
    def canonicalDashboardVersion: IO[Option[Int]]           = IO.pure(version)
    def setCanonicalDashboardVersion(v: Int): IO[Unit]       = IO { version = Some(v) }
    def canonicalDashboardRevision: IO[Option[Int]]          = IO.pure(revision)
    def setCanonicalDashboardRevision(r: Int): IO[Unit]      = IO { revision = Some(r) }
    def canonicalDashboardCards: IO[Set[String]]             = IO.pure(cards)
    def setCanonicalDashboardCards(c: Set[String]): IO[Unit] = IO { cards = c }
    def curated: IO[Boolean]                                 = IO.pure(true)
    def markCurated: IO[Unit]                                = IO.unit
  }

  /** Runs just the dashboard step, with metadata stubbed to whatever placeholders the shipped spec asks for. */
  private def reconcile(state: FakeState): Unit =
    HttpClientFs2Backend
      .resource[IO]()
      .use { backend =>
        for {
          client <- MetabaseClient(config, backend)
          meta    = MetabaseProvisioner.DbMetadata(1, Nil)
          _      <- new MetabaseProvisioner(config, client, state).seedCanonicalDashboard(meta).value
        } yield ()
      }
      .unsafeRunSync()

  private def dashcardsWritten(): List[Json] =
    wm.findAll(putRequestedFor(urlEqualTo("/api/dashboard/7")))
      .asScala
      .toList
      .flatMap(r => parse(r.getBodyAsString).toOption)
      .flatMap(_.hcursor.downField("dashcards").values)
      .lastOption
      .map(_.toList)
      .getOrElse(Nil)

  "A dashboard already at the shipped version" - {
    "is left completely alone" in {
      liveDashboard()
      val state = new FakeState(Some(7), Some(2), Some(42), Set("spent"))
      reconcile(state)
      wm.findAll(putRequestedFor(urlEqualTo("/api/dashboard/7"))) shouldBe empty
      wm.findAll(postRequestedFor(urlEqualTo("/api/dashboard/7/copy"))) shouldBe empty
    }
  }

  "A dashboard nobody has edited" - {
    "is backed up and then replaced with the shipped layout" in {
      liveDashboard()
      val state = new FakeState(Some(7), Some(1), Some(42), Set("spent"))
      reconcile(state)

      wm.findAll(postRequestedFor(urlEqualTo("/api/dashboard/7/copy"))).size shouldBe 1
      // Every card in the spec is recreated, and the user's dashcard is not carried over.
      val written = dashcardsWritten()
      written.size should be > 1
      written.flatMap(_.hcursor.get[Int]("id").toOption) should not contain 100
      state.version shouldBe Some(2)
      state.revision shouldBe Some(42)
    }
  }

  "A dashboard the user has edited" - {
    "keeps every existing card and appends only what is missing" in {
      liveDashboard()
      // The revision moved on since we stamped it: someone has been in there.
      val state = new FakeState(Some(7), Some(1), Some(11), Set("spent"))
      reconcile(state)

      wm.findAll(postRequestedFor(urlEqualTo("/api/dashboard/7/copy"))).size shouldBe 1
      val written = dashcardsWritten()

      // The user's card survives untouched, in its own position.
      val kept = written.find(_.hcursor.get[Int]("id").toOption.contains(100))
      kept.flatMap(_.hcursor.get[Int]("row").toOption) shouldBe Some(4)

      // Everything added lands below it (its bottom edge is row 4 + height 6).
      val added = written.filter(_.hcursor.get[Int]("id").exists(_ < 0))
      added should not be empty
      all(added.flatMap(_.hcursor.get[Int]("row").toOption)) should be >= 10
      state.version shouldBe Some(2)
    }

    "bootstraps the card map by name when the dashboard predates keys" in {
      liveDashboard()
      val state = new FakeState(Some(7), Some(1), Some(11), Set.empty[String])
      reconcile(state)

      // 'Spent' was matched by name, so it is not added a second time.
      val addedNames = wm
        .findAll(postRequestedFor(urlEqualTo("/api/card")))
        .asScala
        .toList
        .flatMap(r => parse(r.getBodyAsString).toOption)
        .flatMap(_.hcursor.get[String]("name").toOption)
      addedNames should not contain "Spent"
      addedNames should contain("Free money over time")
    }
  }

  "A dashboard the user deleted" - {
    "stays deleted, and the version is recorded so it is not retried" in {
      stubFor(get(urlEqualTo("/api/dashboard/7")).willReturn(aResponse().withStatus(404).withBody("not found")))
      val state = new FakeState(Some(7), Some(1), Some(42), Set("spent"))
      reconcile(state)

      wm.findAll(putRequestedFor(urlEqualTo("/api/dashboard/7"))) shouldBe empty
      wm.findAll(postRequestedFor(urlEqualTo("/api/card"))) shouldBe empty
      state.version shouldBe Some(2)
    }
  }
}
