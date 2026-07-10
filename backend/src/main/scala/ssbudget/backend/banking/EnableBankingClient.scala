package ssbudget.backend.banking

import cats.effect.IO
import io.circe.{Decoder, Json}
import io.circe.parser.decode
import io.circe.syntax.*
import ssbudget.shared.api.Aspsp
import sttp.client3.*
import sttp.model.Uri

import java.time.temporal.ChronoUnit
import java.time.{Instant, LocalDate, OffsetDateTime, ZoneOffset}
import scala.util.Try

/** The operations [[ssbudget.backend.banking.BankingService]] needs from Enable Banking. Abstracted as a trait so tests can supply a fake without
  * real HTTP. The live implementation is [[EnableBankingClient]].
  */
trait EnableBankingApi {
  import EnableBankingClient.*
  def listAspsps(country: Option[String]): IO[Either[String, List[Aspsp]]]
  def startAuthorization(aspspName: String, aspspCountry: String, state: String): IO[Either[String, String]]
  def createSession(code: String): IO[Either[String, EbSession]]
  def getBalances(accountUid: String): IO[Either[String, EbBalance]]
  def getAccountDetails(accountUid: String): IO[Either[String, EbAccountDetails]]
  def getTransactions(
      accountUid: String,
      dateFrom: LocalDate,
      dateTo: LocalDate,
      continuationKey: Option[String],
  ): IO[Either[String, EbTransactionsPage]]
  def deleteSession(sessionId: String): IO[Either[String, Unit]]
}

/** Thin anti-corruption layer over the Enable Banking REST API. Only this package knows Enable Banking's wire shapes; everything above works on our
  * own domain types. Mirrors the outbound-HTTP style of [[ssbudget.backend.service.CurrencyService]].
  */
class EnableBankingClient(config: EnableBankingConfig, jwt: EnableBankingJwt, backend: SttpBackend[IO, Any]) extends EnableBankingApi {

  import EnableBankingClient.*

  /** GET /aspsps — list connectable banks, optionally filtered by country. */
  def listAspsps(country: Option[String]): IO[Either[String, List[Aspsp]]] =
    get(Seq("aspsps"), country.map("country" -> _).toMap).map(_.flatMap(parseAspsps))

  /** POST /auth — start authorization; returns the URL the user must be redirected to for SCA at their bank. */
  def startAuthorization(aspspName: String, aspspCountry: String, state: String): IO[Either[String, String]] =
    IO.realTimeInstant.flatMap { now =>
      val validUntil = now.plus(90, ChronoUnit.DAYS)
      val body       = Json.obj(
        "access"       -> Json.obj("valid_until" -> validUntil.toString.asJson),
        "aspsp"        -> Json.obj("name" -> aspspName.asJson, "country" -> aspspCountry.asJson),
        "state"        -> state.asJson,
        "redirect_url" -> config.redirectUrl.asJson,
        "psu_type"     -> "personal".asJson,
      )
      post(Seq("auth"), body).map(_.flatMap(parseAuthUrl))
    }

  /** POST /sessions — exchange the authorization code for a session and the authorized accounts. */
  def createSession(code: String): IO[Either[String, EbSession]] =
    post(Seq("sessions"), Json.obj("code" -> code.asJson)).map(_.flatMap(parseSession))

  /** GET /accounts/{uid}/balances — returns the account's current balance (best available balance type). */
  def getBalances(accountUid: String): IO[Either[String, EbBalance]] =
    get(Seq("accounts", accountUid, "balances"), Map.empty).map(_.flatMap(parseBalances))

  /** GET /accounts/{uid}/details — account name, product, IBAN, currency. */
  def getAccountDetails(accountUid: String): IO[Either[String, EbAccountDetails]] =
    get(Seq("accounts", accountUid, "details"), Map.empty).map(_.flatMap(parseAccountDetails))

  /** GET /accounts/{uid}/transactions — one page of transactions in [dateFrom, dateTo], following `continuation_key` for the next page. */
  def getTransactions(
      accountUid: String,
      dateFrom: LocalDate,
      dateTo: LocalDate,
      continuationKey: Option[String],
  ): IO[Either[String, EbTransactionsPage]] = {
    val params = Map("date_from" -> dateFrom.toString, "date_to" -> dateTo.toString) ++ continuationKey.map("continuation_key" -> _)
    get(Seq("accounts", accountUid, "transactions"), params).map(_.flatMap(parseTransactions))
  }

  /** DELETE /sessions/{id} — revoke the consent at the bank. Best-effort. */
  def deleteSession(sessionId: String): IO[Either[String, Unit]] =
    jwt.token.flatMap { t =>
      backend
        .send(basicRequest.delete(buildUri(Seq("sessions", sessionId), Map.empty)).auth.bearer(t).response(asString))
        .map(_.body.left.map(e => s"EB DELETE sessions/$sessionId failed: $e").map(_ => ()))
    }

  private def buildUri(segments: Seq[String], params: Map[String, String]): Uri = {
    val base = uri"${config.baseUrl}".addPath(segments)
    params.foldLeft(base) { case (u, (k, v)) => u.addParam(k, v) }
  }

  private def get(segments: Seq[String], params: Map[String, String]): IO[Either[String, String]] =
    jwt.token.flatMap { t =>
      backend
        .send(basicRequest.get(buildUri(segments, params)).auth.bearer(t).response(asString))
        .map(_.body.left.map(e => s"EB GET ${segments.mkString("/")} failed: $e"))
    }

  private def post(segments: Seq[String], body: Json): IO[Either[String, String]] =
    jwt.token.flatMap { t =>
      backend
        .send(
          basicRequest
            .post(buildUri(segments, Map.empty))
            .auth
            .bearer(t)
            .contentType("application/json")
            .body(body.noSpaces)
            .response(asString),
        )
        .map(_.body.left.map(e => s"EB POST ${segments.mkString("/")} failed: $e"))
    }
}

object EnableBankingClient {

  /** An account authorized within an Enable Banking session. Enable Banking's shape varies by bank, so all metadata besides the uid is optional. */
  final case class EbAccountRef(uid: String, name: Option[String], currency: Option[String], iban: Option[String])

  final case class EbSession(sessionId: String, accounts: List[EbAccountRef], validUntil: Option[Instant])

  /** A resolved account balance, normalised to integer cents. */
  final case class EbBalance(amountCents: Long, currency: String)

  final case class EbAccountDetails(name: Option[String], product: Option[String], iban: Option[String], currency: Option[String])

  /** One transaction, best-effort extracted. `amountCents` is signed (debit negative). `raw` is the untouched payload so nothing is ever lost. */
  final case class EbTransaction(
      entryReference: Option[String],
      amountCents: Long,
      currency: String,
      status: String, // "booked" | "pending"
      bookedAt: Option[Instant],
      counterpartyName: Option[String],
      counterpartyAccount: Option[String],
      remittance: Option[String],
      bankTransactionCode: Option[String],
      raw: Json,
  )

  final case class EbTransactionsPage(transactions: List[EbTransaction], continuationKey: Option[String])

  // Preferred balance types, best-first (closing available, interim available, closing booked, expected).
  private val balanceTypePreference = List("CLAV", "ITAV", "CLBD", "XPCD")

  private given Decoder[EbAccountRef] = Decoder.instance { c =>
    // Some banks return account uids as plain strings, others as full objects.
    c.as[String] match {
      case Right(uid) => Right(EbAccountRef(uid, None, None, None))
      case Left(_)    =>
        for {
          uid       <- c.get[String]("uid")
          name      <- c.get[Option[String]]("name")
          currency  <- c.get[Option[String]]("currency")
          accountId <- c.get[Option[Json]]("account_id")
        } yield {
          val iban = accountId.flatMap(_.hcursor.get[String]("iban").toOption)
          EbAccountRef(uid, name, currency, iban)
        }
    }
  }

  private def parseAspsps(body: String): Either[String, List[Aspsp]] =
    decode[Json](body).left
      .map(e => s"Failed to parse aspsps: ${e.getMessage}")
      .flatMap(_.hcursor.get[List[Aspsp]]("aspsps").left.map(e => s"Failed to read aspsps: ${e.getMessage}"))

  private def parseAuthUrl(body: String): Either[String, String] =
    decode[Json](body).left
      .map(e => s"Failed to parse auth response: ${e.getMessage}")
      .flatMap(_.hcursor.get[String]("url").left.map(_ => s"Auth response missing 'url': $body"))

  private def parseSession(body: String): Either[String, EbSession] =
    decode[Json](body).left.map(e => s"Failed to parse session: ${e.getMessage}").flatMap { json =>
      val c = json.hcursor
      for {
        sessionId <- c.get[String]("session_id").left.map(_ => s"Session response missing 'session_id': $body")
      } yield {
        val accounts   = c.get[List[EbAccountRef]]("accounts").getOrElse(Nil)
        val validUntil = c
          .get[Option[Json]]("access")
          .toOption
          .flatten
          .flatMap(_.hcursor.get[String]("valid_until").toOption)
          .flatMap(parseInstant)
        EbSession(sessionId, accounts, validUntil)
      }
    }

  private def parseBalances(body: String): Either[String, EbBalance] =
    decode[Json](body).left.map(e => s"Failed to parse balances: ${e.getMessage}").flatMap { json =>
      json.hcursor.get[List[Json]]("balances").left.map(_ => s"Balances response missing 'balances': $body").flatMap { balances =>
        pickBalance(balances).toRight(s"No usable balance in: $body").flatMap(toEbBalance)
      }
    }

  private def pickBalance(balances: List[Json]): Option[Json] = {
    def typeOf(j: Json): Option[String] = j.hcursor.get[String]("balance_type").toOption
    balanceTypePreference.iterator
      .map(pref => balances.find(b => typeOf(b).contains(pref)))
      .collectFirst { case Some(b) => b }
      .orElse(balances.headOption)
  }

  private def toEbBalance(balance: Json): Either[String, EbBalance] = {
    val amountCursor = balance.hcursor.downField("balance_amount")
    for {
      amountJson <- amountCursor.get[Json]("amount").left.map(_ => "balance_amount.amount missing")
      currency   <- amountCursor.get[String]("currency").left.map(_ => "balance_amount.currency missing")
      amountStr   = amountJson.asString.orElse(amountJson.asNumber.map(_.toString)).getOrElse(amountJson.noSpaces)
      cents      <- Try((BigDecimal(amountStr) * 100).setScale(0, BigDecimal.RoundingMode.HALF_UP).toLongExact).toEither.left
                      .map(e => s"Invalid amount '$amountStr': ${e.getMessage}")
    } yield EbBalance(cents, currency)
  }

  private def parseAccountDetails(body: String): Either[String, EbAccountDetails] =
    decode[Json](body).left.map(e => s"Failed to parse account details: ${e.getMessage}").map { json =>
      val c = json.hcursor
      EbAccountDetails(
        // PKO leaves `name` empty and puts the human label in `details` (e.g. "GŁÓWNE", "OSZCZĘDNOŚCI"); fall back to it.
        name = c.get[String]("name").toOption.filter(_.nonEmpty).orElse(c.get[String]("details").toOption.filter(_.nonEmpty)),
        product = c.get[String]("product").toOption.filter(_.nonEmpty),
        iban = c.downField("account_id").get[String]("iban").toOption.filter(_.nonEmpty),
        currency = c.get[String]("currency").toOption.filter(_.nonEmpty),
      )
    }

  private def parseTransactions(body: String): Either[String, EbTransactionsPage] =
    decode[Json](body).left.map(e => s"Failed to parse transactions: ${e.getMessage}").map { json =>
      val c            = json.hcursor
      val continuation = c.get[String]("continuation_key").toOption.filter(_.nonEmpty)
      val txs          = c.get[List[Json]]("transactions").getOrElse(Nil).map(parseTransaction)
      EbTransactionsPage(txs, continuation)
    }

  /** Total (never throws): unknown/oddly-shaped fields simply come back empty; the full payload is kept in `raw`. */
  private def parseTransaction(j: Json): EbTransaction = {
    val c                                             = j.hcursor
    val amountStr                                     = c
      .downField("transaction_amount")
      .get[Json]("amount")
      .toOption
      .flatMap(a => a.asString.orElse(a.asNumber.map(_.toString)))
    val rawCents                                      = amountStr.flatMap(s => Try((BigDecimal(s) * 100).setScale(0, BigDecimal.RoundingMode.HALF_UP).toLongExact).toOption).getOrElse(0L)
    val indicator                                     = c.get[String]("credit_debit_indicator").toOption
    val signed                                        = indicator match {
      case Some("DBIT") => -math.abs(rawCents)
      case Some("CRDT") => math.abs(rawCents)
      case _            => rawCents // fall back to whatever sign the amount carried
    }
    val currency                                      = c.downField("transaction_amount").get[String]("currency").toOption.getOrElse("")
    val status                                        = c.get[String]("status").toOption match {
      case Some("PDNG") => "pending"
      case _            => "booked"
    }
    val bookedAt                                      = List("booking_date", "value_date", "transaction_date").iterator
      .flatMap(f => c.get[String](f).toOption)
      .flatMap(parseInstant)
      .nextOption()
    // The counterparty is the OTHER party: for an outgoing (DBIT) payment it's the creditor (payee); for an incoming (CRDT) one it's the debtor
    // (payer). Never fall back to our own side — otherwise an outgoing purchase whose payee has no IBAN (BLIK, card) would surface OUR account as the
    // counterparty and be misread as an internal transfer.
    def fld(obj: String, key: String): Option[String] = c.downField(obj).get[String](key).toOption.filter(_.nonEmpty)
    val (name, acct)                                  = indicator match {
      case Some("DBIT") => (fld("creditor", "name"), fld("creditor_account", "iban"))
      case Some("CRDT") => (fld("debtor", "name"), fld("debtor_account", "iban"))
      case _            => (fld("creditor", "name").orElse(fld("debtor", "name")), fld("creditor_account", "iban").orElse(fld("debtor_account", "iban")))
    }
    val remittance                                    = c
      .get[List[String]]("remittance_information")
      .toOption
      .map(_.mkString(" "))
      .filter(_.nonEmpty)
      .orElse(c.get[String]("remittance_information_unstructured").toOption.filter(_.nonEmpty))
    val btc                                           = c
      .downField("bank_transaction_code")
      .get[String]("description")
      .toOption
      .orElse(c.downField("bank_transaction_code").get[String]("code").toOption)
      .orElse(c.get[String]("bank_transaction_code").toOption)
      .filter(_.nonEmpty)
    val entryRef                                      = c.get[String]("entry_reference").toOption.filter(_.nonEmpty)
    EbTransaction(entryRef, signed, currency, status, bookedAt, name, acct, remittance, btc, j)
  }

  private def parseInstant(s: String): Option[Instant] =
    Try(Instant.parse(s)).toOption
      .orElse(Try(OffsetDateTime.parse(s).toInstant).toOption)
      .orElse(Try(LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant).toOption)
}
