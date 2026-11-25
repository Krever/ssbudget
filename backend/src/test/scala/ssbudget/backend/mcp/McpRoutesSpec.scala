package ssbudget.backend.mcp

import cats.effect.IO
import cats.implicits.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import org.http4s.*
import org.http4s.circe.*
import org.http4s.implicits.*
import org.typelevel.ci.*
import ssbudget.backend.auth.McpTokenService
import ssbudget.backend.banking.{CategorizationService, RuleEngineService}
import ssbudget.backend.db.Repositories
import ssbudget.backend.db.repository.RepositorySpec
import ssbudget.shared.model.*

import java.time.Instant

class McpRoutesSpec extends RepositorySpec {

  /** The token that `fixtures` makes. Each test has its own database, and so its own token. */
  private var token = ""
  private val at    = Instant.parse("2026-01-10T00:00:00Z")

  private def tx(
      id: String,
      name: String,
      day: Int,
      internal: Boolean = false,
      categoryId: Option[CategoryId] = None,
      categorySource: Option[CategorySource] = None,
  ): BankTransaction =
    BankTransaction(
      BankTransactionId(id),
      BankConnectionId("c-1"),
      "uid-1",
      Some(id),
      id,
      -1000,
      Currency.PLN,
      TransactionStatus.Booked,
      at.plusSeconds(day * 86400L),
      Some(name),
      None,
      None,
      None,
      categoryId,
      """{"secret":"raw payload"}""",
      at,
      internal,
      categorySource,
    )

  /** Two categories, and four transactions without a category: three of one shop, one of a second shop. */
  private def fixtures: IO[(Repositories, HttpApp[IO])] = {
    val repos  = Repositories.fromTransactor(xa)
    val engine = new RuleEngineService(repos)
    val tools  = new CategorizationTools(repos, new CategorizationService(repos, engine))
    val server = new McpServer(tools.all, List(CategorizationGuide.prompt), CategorizationGuide.instructions)
    val auth   = new McpTokenService(repos.mcpTokens)
    for {
      made <- auth.create("test")
      _     = token = made.toOption.get.token
      _    <- repos.categories.create(Category(CategoryId("groceries"), "Groceries", None))
      _    <- repos.categories.create(Category(CategoryId("fuel"), "Fuel", None))
      _    <- repos.bankTransactions.insertNew(tx("t1", "Biedronka 12", day = 1))
      _    <- repos.bankTransactions.insertNew(tx("t2", "Biedronka 7", day = 2))
      _    <- repos.bankTransactions.insertNew(tx("t3", "BIEDRONKA  12", day = 3))
      _    <- repos.bankTransactions.insertNew(tx("t4", "Orlen", day = 4))
      _    <- repos.bankTransactions.insertNew(tx("t5", "Own account", day = 5, internal = true))
    } yield (repos, McpRoutes.make(auth, server).orNotFound)
  }

  private def post(body: Json, headers: Header.ToRaw*): Request[IO] =
    Request[IO](Method.POST, uri"/mcp").withEntity(body).putHeaders(headers*)

  private def bearer(value: => String = token): Header.Raw = Header.Raw(ci"Authorization", s"Bearer $value")

  private def rpc(method: String, params: Json = Json.obj(), id: Int = 1): Json =
    Json.obj("jsonrpc" := "2.0", "id" := id, "method" := method, "params" := params)

  private val ping = rpc("ping")

  /** Calls one tool with the token. Returns `isError` and the text content, parsed as JSON when it is JSON. */
  private def call(app: HttpApp[IO], tool: String, args: Json = Json.obj()): IO[(Boolean, Json)] =
    app.run(post(rpc("tools/call", Json.obj("name" := tool, "arguments" := args)), bearer())).flatMap(_.as[Json]).map { json =>
      val result = json.hcursor.downField("result")
      val text   = result.downField("content").downArray.get[String]("text").toOption.get
      (result.get[Boolean]("isError").toOption.get, io.circe.parser.parse(text).getOrElse(Json.fromString(text)))
    }

  private val biedronka = Json.arr(Json.obj("kind" := "counterpartyName", "op" := "contains", "value" := "biedronka"))

  "authentication" - {
    "refuses a request without a token" in {
      for {
        (_, app) <- fixtures
        res      <- app.run(post(ping))
      } yield {
        res.status shouldBe Status.Unauthorized
        res.headers.get(ci"WWW-Authenticate").map(_.head.value).getOrElse("") should startWith("Bearer")
      }
    }

    "refuses a wrong token, also one that starts with the right token" in {
      for {
        (_, app) <- fixtures
        wrong    <- app.run(post(ping, bearer("x" * 40)))
        longer   <- app.run(post(ping, bearer(token + "t")))
        shorter  <- app.run(post(ping, bearer(token.drop(1))))
        empty    <- app.run(post(ping, bearer("")))
      } yield List(wrong, longer, shorter, empty).map(_.status).distinct shouldBe List(Status.Unauthorized)
    }

    "refuses the right token under a different scheme, and two Authorization headers" in {
      for {
        (_, app) <- fixtures
        basic    <- app.run(post(ping, Header.Raw(ci"Authorization", s"Basic $token")))
        bare     <- app.run(post(ping, Header.Raw(ci"Authorization", token)))
        twice    <- app.run(post(ping).withHeaders(Headers(bearer(), bearer("x" * 40))))
      } yield List(basic, bare, twice).map(_.status).distinct shouldBe List(Status.Unauthorized)
    }

    "does not accept the token in a cookie or in the query" in {
      for {
        (_, app) <- fixtures
        cookie   <- app.run(post(ping, Header.Raw(ci"Cookie", s"session=$token; token=$token")))
        query    <- app.run(Request[IO](Method.POST, uri"/mcp".withQueryParam("token", token)).withEntity(ping))
      } yield List(cookie, query).map(_.status).distinct shouldBe List(Status.Unauthorized)
    }

    "answers 401 to each method when there is no token, so the endpoint tells nothing else" in {
      for {
        (_, app) <- fixtures
        get      <- app.run(Request[IO](Method.GET, uri"/mcp"))
        delete   <- app.run(Request[IO](Method.DELETE, uri"/mcp"))
      } yield List(get, delete).map(_.status).distinct shouldBe List(Status.Unauthorized)
    }

    "accepts the right token" in {
      for {
        (_, app) <- fixtures
        res      <- app.run(post(ping, bearer()))
        lower    <- app.run(post(ping, Header.Raw(ci"Authorization", s"bearer $token")))
      } yield {
        res.status shouldBe Status.Ok
        lower.status shouldBe Status.Ok
        res.headers.get(ci"Cache-Control").map(_.head.value) shouldBe Some("no-store")
      }
    }

    "a token is 256 random bits, and the database holds only its digest" in {
      for {
        (repos, _) <- fixtures
        rows       <- repos.mcpTokens.findAll
        second     <- new McpTokenService(repos.mcpTokens).create("second")
      } yield {
        token should startWith(McpTokenService.TokenPrefix)
        java.util.Base64.getUrlDecoder.decode(token.stripPrefix(McpTokenService.TokenPrefix)).length shouldBe 32
        second.toOption.get.token should not be token
        rows.map(_.name) shouldBe List("test")
        rows.head.tokenHash should have length 64
        rows.head.toString should not include token.stripPrefix(McpTokenService.TokenPrefix)
      }
    }

    "a revoked token is refused at once, and a different token still works" in {
      for {
        (repos, app) <- fixtures
        auth          = new McpTokenService(repos.mcpTokens)
        other        <- auth.create("laptop").map(_.toOption.get)
        before       <- app.run(post(ping, bearer(other.token)))
        _            <- auth.revoke(other.info.id)
        after        <- app.run(post(ping, bearer(other.token)))
        first        <- app.run(post(ping, bearer()))
      } yield {
        before.status shouldBe Status.Ok
        after.status shouldBe Status.Unauthorized
        first.status shouldBe Status.Ok
      }
    }

    "with no token in the table, nothing passes" in {
      for {
        (repos, app) <- fixtures
        auth          = new McpTokenService(repos.mcpTokens)
        _            <- auth.list.flatMap(_.traverse(t => auth.revoke(t.id)))
        res          <- app.run(post(ping, bearer()))
        empty        <- app.run(post(ping, bearer(McpTokenService.TokenPrefix)))
      } yield List(res, empty).map(_.status).distinct shouldBe List(Status.Unauthorized)
    }

    "a use is recorded on the token" in {
      for {
        (repos, app) <- fixtures
        before       <- repos.mcpTokens.findAll
        _            <- app.run(post(ping, bearer()))
        after        <- repos.mcpTokens.findAll
      } yield {
        before.head.lastUsedAt shouldBe None
        after.head.lastUsedAt.isDefined shouldBe true
      }
    }

    "the number of tokens and the length of a name have a limit" in {
      for {
        (repos, _) <- fixtures
        auth        = new McpTokenService(repos.mcpTokens)
        longName   <- auth.create("x" * (McpTokenService.MaxNameLength + 1))
        made       <- (2 to McpTokenService.MaxTokens).toList.traverse(i => auth.create(s"t$i"))
        oneMore    <- auth.create("too many")
      } yield {
        longName.isLeft shouldBe true
        made.forall(_.isRight) shouldBe true
        oneMore.isLeft shouldBe true
      }
    }
  }

  "transport" - {
    "refuses a request that has an Origin header, with the right token too" in {
      for {
        (_, app) <- fixtures
        res      <- app.run(post(ping, bearer(), Header.Raw(ci"Origin", "https://evil.example")))
      } yield res.status shouldBe Status.Forbidden
    }

    "accepts only POST" in {
      for {
        (_, app) <- fixtures
        res      <- app.run(Request[IO](Method.GET, uri"/mcp").putHeaders(bearer()))
      } yield res.status shouldBe Status.MethodNotAllowed
    }

    "refuses a body above the limit" in {
      val big = rpc("ping", Json.obj("pad" := "x" * (McpRoutes.MaxBodyBytes + 1)))
      for {
        (_, app) <- fixtures
        res      <- app.run(post(big, bearer()))
      } yield res.status shouldBe Status.PayloadTooLarge
    }

    "refuses a body that is not one JSON object" in {
      for {
        (_, app) <- fixtures
        notJson  <- app.run(Request[IO](Method.POST, uri"/mcp").withEntity("{nope").putHeaders(bearer()))
        batch    <- app.run(post(Json.arr(ping), bearer()))
      } yield List(notJson, batch).map(_.status).distinct shouldBe List(Status.BadRequest)
    }

    "does not answer other paths" in {
      for {
        (_, app) <- fixtures
        res      <- app.run(Request[IO](Method.POST, uri"/mcp/other").withEntity(ping).putHeaders(bearer()))
      } yield res.status shouldBe Status.NotFound
    }
  }

  "protocol" - {
    "initialize gives a supported version, the capabilities and the instructions" in {
      for {
        (_, app) <- fixtures
        known    <- app.run(post(rpc("initialize", Json.obj("protocolVersion" := "2025-03-26")), bearer())).flatMap(_.as[Json])
        unknown  <- app.run(post(rpc("initialize", Json.obj("protocolVersion" := "2099-01-01")), bearer())).flatMap(_.as[Json])
      } yield {
        val result = known.hcursor.downField("result")
        result.get[String]("protocolVersion") shouldBe Right("2025-03-26")
        result.downField("capabilities").keys.map(_.toSet) shouldBe Some(Set("tools", "prompts"))
        result.get[String]("instructions").map(_.nonEmpty) shouldBe Right(true)
        unknown.hcursor.downField("result").get[String]("protocolVersion") shouldBe Right(McpServer.SupportedVersions.head)
      }
    }

    "a notification gets 202 and no body" in {
      for {
        (_, app) <- fixtures
        res      <- app.run(post(Json.obj("jsonrpc" := "2.0", "method" := "notifications/initialized"), bearer()))
        body     <- res.as[String]
      } yield {
        res.status shouldBe Status.Accepted
        body shouldBe ""
      }
    }

    "an unknown method is a JSON-RPC error" in {
      for {
        (_, app) <- fixtures
        json     <- app.run(post(rpc("resources/list"), bearer())).flatMap(_.as[Json])
      } yield json.hcursor.downField("error").get[Int]("code") shouldBe Right(McpServer.MethodNotFound)
    }

    "the token gives the categorization tools and no others" in {
      for {
        (_, app) <- fixtures
        json     <- app.run(post(rpc("tools/list"), bearer())).flatMap(_.as[Json])
      } yield {
        val tools    = json.hcursor.downField("result").downField("tools").as[List[JsonObject]].toOption.get
        tools.flatMap(_("name")).flatMap(_.asString).toSet shouldBe Set(
          "list_uncategorized",
          "list_categories",
          "list_rules",
          "preview_rule",
          "create_rule",
          "update_rule",
          "delete_rule",
          "set_categories",
          "create_category",
        )
        val readOnly = tools.filter(_("annotations").flatMap(_.hcursor.get[Boolean]("readOnlyHint").toOption).contains(true))
        readOnly.flatMap(_("name")).flatMap(_.asString).toSet shouldBe Set("list_uncategorized", "list_categories", "list_rules", "preview_rule")
      }
    }

    "the procedure is available as a prompt" in {
      for {
        (_, app) <- fixtures
        json     <- app.run(post(rpc("prompts/get", Json.obj("name" := "categorize_transactions")), bearer())).flatMap(_.as[Json])
      } yield {
        val text = json.hcursor.downField("result").downField("messages").downArray.downField("content").get[String]("text")
        text.map(_.contains("preview_rule")) shouldBe Right(true)
      }
    }
  }

  "tools" - {
    "list_uncategorized gives the batch, the total and the groups, without internal transfers and without the raw payload" in {
      for {
        (_, app)     <- fixtures
        (err, first) <- call(app, "list_uncategorized", Json.obj("limit" := 2))
        (_, second)  <- call(app, "list_uncategorized", Json.obj("limit" := 2, "offset" := 2))
      } yield {
        err shouldBe false
        first.hcursor.get[Int]("total") shouldBe Right(4)
        first.hcursor.downField("transactions").as[List[Json]].map(_.flatMap(_.hcursor.get[String]("id").toOption)) shouldBe Right(List("t4", "t3"))
        second.hcursor.downField("transactions").as[List[Json]].map(_.flatMap(_.hcursor.get[String]("id").toOption)) shouldBe Right(List("t2", "t1"))
        // "Biedronka 12" and "BIEDRONKA  12" are one group; the two others occur once and are not groups.
        first.hcursor.downField("groups").as[List[Json]].map(_.map(_.hcursor.get[Int]("count").toOption)) shouldBe Right(List(Some(2)))
        first.noSpaces should not include "raw payload"
        first.hcursor.downField("transactions").downArray.get[String]("amount") shouldBe Right("-10.00")
      }
    }

    "preview_rule shows the effect and writes nothing" in {
      for {
        (repos, app)   <- fixtures
        (err, preview) <- call(app, "preview_rule", Json.obj("categoryId" := "groceries", "criteria" := biedronka))
        rules          <- repos.classificationRules.findAll
        (_, remaining) <- call(app, "list_uncategorized")
      } yield {
        err shouldBe false
        preview.hcursor.get[Int]("matched") shouldBe Right(3)
        preview.hcursor.downField("changes").get[Int]("count") shouldBe Right(3)
        preview.hcursor.downField("changes").downField("transitions").downArray.get[String]("to") shouldBe Right("Groceries")
        rules shouldBe Nil
        remaining.hcursor.get[Int]("total") shouldBe Right(4)
      }
    }

    "create_rule categorizes all transactions that match" in {
      for {
        (repos, app)  <- fixtures
        (err, result) <- call(app, "create_rule", Json.obj("name" := "Biedronka", "categoryId" := "groceries", "criteria" := biedronka))
        t1            <- repos.bankTransactions.findById(BankTransactionId("t1"))
      } yield {
        err shouldBe false
        result.hcursor.get[Int]("uncategorizedRemaining") shouldBe Right(1)
        t1.flatMap(_.categoryId) shouldBe Some(CategoryId("groceries"))
        t1.flatMap(_.categorySource) shouldBe Some(CategorySource.Rule)
      }
    }

    "a preview names the earlier rule that wins and the manual category that stays; `position` puts the rule before it" in {
      val twelve = Json.arr(Json.obj("kind" := "counterpartyName", "op" := "contains", "value" := "biedronka 12"))
      for {
        (repos, app) <- fixtures
        _            <- call(app, "create_rule", Json.obj("name" := "Biedronka", "categoryId" := "groceries", "criteria" := biedronka))
        _            <- call(app, "set_categories", Json.obj("assignments" := List(Json.obj("transactionId" := "t3", "categoryId" := "groceries"))))
        (_, last)    <- call(app, "preview_rule", Json.obj("categoryId" := "fuel", "criteria" := twelve))
        (_, first)   <- call(app, "preview_rule", Json.obj("categoryId" := "fuel", "criteria" := twelve, "position" := 0))
        (err, _)     <- call(app, "create_rule", Json.obj("name" := "B12", "categoryId" := "fuel", "criteria" := twelve, "position" := 0))
        rules        <- repos.classificationRules.findAll
        t1           <- repos.bankTransactions.findById(BankTransactionId("t1"))
        t3           <- repos.bankTransactions.findById(BankTransactionId("t3"))
      } yield {
        last.hcursor.get[Int]("matched") shouldBe Right(2)
        last.hcursor.downField("changes").get[Int]("count") shouldBe Right(0)
        last.hcursor.downField("takenByEarlierRule").get[Int]("count") shouldBe Right(1)
        last.hcursor.downField("keptManual").get[Int]("count") shouldBe Right(1)
        first.hcursor.downField("changes").get[Int]("count") shouldBe Right(1)
        first.hcursor.downField("changes").downField("transitions").downArray.get[String]("from") shouldBe Right("Groceries")
        err shouldBe false
        rules.map(_.name) shouldBe List("B12", "Biedronka")
        // Before the new rule, the two matched transactions were both in Groceries (one by rule, one manual).
        last.hcursor.downField("matchedByCategory").downArray.get[Int]("count") shouldBe Right(2)
        t1.flatMap(_.categoryId) shouldBe Some(CategoryId("fuel"))
        t3.flatMap(_.categoryId) shouldBe Some(CategoryId("groceries")) // manual: no rule changes it
      }
    }

    "update_rule keeps the fields that are not given, and delete_rule gives the transactions back" in {
      for {
        (repos, app)   <- fixtures
        (_, created)   <- call(app, "create_rule", Json.obj("name" := "Biedronka", "categoryId" := "groceries", "criteria" := biedronka))
        id              = created.hcursor.downField("rule").get[String]("id").toOption.get
        (err, updated) <- call(app, "update_rule", Json.obj("ruleId" := id, "categoryId" := "fuel"))
        t1             <- repos.bankTransactions.findById(BankTransactionId("t1"))
        (_, preview)   <- call(app, "preview_rule", Json.obj("ruleId" := id, "delete" := true))
        (_, deleted)   <- call(app, "delete_rule", Json.obj("ruleId" := id))
      } yield {
        err shouldBe false
        updated.hcursor.downField("rule").get[String]("name") shouldBe Right("Biedronka")
        t1.flatMap(_.categoryId) shouldBe Some(CategoryId("fuel"))
        preview.hcursor.downField("changes").downField("transitions").downArray.get[String]("to") shouldBe Right("(uncategorized)")
        deleted.hcursor.get[Int]("uncategorizedRemaining") shouldBe Right(4)
      }
    }

    "list_rules finds rules by text and by category" in {
      val orlen             = Json.arr(Json.obj("kind" := "counterpartyName", "op" := "equals", "value" := "Orlen"))
      def names(json: Json) = json.hcursor.downField("rules").as[List[Json]].map(_.flatMap(_.hcursor.get[String]("name").toOption))
      for {
        (_, app)     <- fixtures
        _            <- call(app, "create_rule", Json.obj("name" := "Shop", "categoryId" := "groceries", "criteria" := biedronka))
        _            <- call(app, "create_rule", Json.obj("name" := "Petrol", "categoryId" := "fuel", "criteria" := orlen))
        (_, all)     <- call(app, "list_rules")
        (_, byText)  <- call(app, "list_rules", Json.obj("query" := "ORLEN"))
        (_, byCat)   <- call(app, "list_rules", Json.obj("categoryId" := "groceries"))
        (_, byName)  <- call(app, "list_rules", Json.obj("query" := "fuel"))
        (_, nothing) <- call(app, "list_rules", Json.obj("query" := "lidl"))
      } yield {
        names(all) shouldBe Right(List("Shop", "Petrol"))
        names(byText) shouldBe Right(List("Petrol"))
        byText.hcursor.downField("rules").downArray.get[Int]("position") shouldBe Right(1)
        names(byCat) shouldBe Right(List("Shop"))
        names(byName) shouldBe Right(List("Petrol"))
        nothing.hcursor.get[Int]("total") shouldBe Right(0)
      }
    }

    "a rule that would match everything is refused" in {
      val blank = Json.arr(Json.obj("kind" := "counterpartyName", "op" := "contains", "value" := "  "))
      for {
        (repos, app) <- fixtures
        (err, _)     <- call(app, "create_rule", Json.obj("name" := "All", "categoryId" := "groceries", "criteria" := blank))
        (err2, _)    <- call(app, "create_rule", Json.obj("name" := "None", "categoryId" := "groceries", "criteria" := Json.arr()))
        (err3, _)    <- call(app, "create_rule", Json.obj("name" := "Bad", "categoryId" := "nope", "criteria" := biedronka))
        (err4, _)    <- call(app, "create_rule", Json.obj("name" := "Bad", "categoryId" := "groceries", "criteria" := Json.arr(Json.obj("kind" := "x"))))
        rules        <- repos.classificationRules.findAll
      } yield {
        List(err, err2, err3, err4) shouldBe List(true, true, true, true)
        rules shouldBe Nil
      }
    }

    "set_categories writes all assignments or none" in {
      def assign(pairs: (String, String)*): Json =
        Json.obj("assignments" := pairs.map { case (t, c) => Json.obj("transactionId" := t, "categoryId" := c) })
      for {
        (repos, app)  <- fixtures
        (bad, _)      <- call(app, "set_categories", assign("t4" -> "fuel", "missing" -> "fuel"))
        afterBad      <- repos.bankTransactions.findById(BankTransactionId("t4"))
        (badCat, _)   <- call(app, "set_categories", assign("t4" -> "nope"))
        (err, result) <- call(app, "set_categories", assign("t4" -> "fuel", "t1" -> "groceries"))
        t4            <- repos.bankTransactions.findById(BankTransactionId("t4"))
      } yield {
        bad shouldBe true
        badCat shouldBe true
        afterBad.flatMap(_.categoryId) shouldBe None
        err shouldBe false
        result.hcursor.get[Int]("updated") shouldBe Right(2)
        result.hcursor.get[Int]("uncategorizedRemaining") shouldBe Right(2)
        t4.flatMap(_.categorySource) shouldBe Some(CategorySource.Manual)
      }
    }

    "create_category refuses a name that exists" in {
      for {
        (repos, app) <- fixtures
        (dup, _)     <- call(app, "create_category", Json.obj("name" := "groceries"))
        (err, made)  <- call(app, "create_category", Json.obj("name" := " Pets "))
        cats         <- repos.categories.findAll
      } yield {
        dup shouldBe true
        err shouldBe false
        made.hcursor.get[String]("name") shouldBe Right("Pets")
        cats.map(_.name).toSet shouldBe Set("Groceries", "Fuel", "Pets")
      }
    }

    "an unknown tool is a protocol error" in {
      for {
        (_, app) <- fixtures
        json     <- app.run(post(rpc("tools/call", Json.obj("name" := "export_database")), bearer())).flatMap(_.as[Json])
      } yield json.hcursor.downField("error").get[Int]("code") shouldBe Right(McpServer.InvalidParams)
    }
  }
}
