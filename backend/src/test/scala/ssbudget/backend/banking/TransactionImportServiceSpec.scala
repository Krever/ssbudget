package ssbudget.backend.banking

import cats.effect.IO
import io.circe.Json
import ssbudget.backend.banking.EnableBankingClient.{EbTransaction, EbTransactionsPage}
import ssbudget.backend.db.Repositories
import ssbudget.backend.db.repository.RepositorySpec
import ssbudget.shared.api.ImportTransactionsRequest
import ssbudget.shared.model.*

import java.time.{Instant, LocalDate, ZoneOffset}

/** Fake that serves pre-canned pages per account uid (following continuationKey) and records the last requested `dateFrom`. */
class PagingFakeEb(pagesByUid: Map[String, List[EbTransactionsPage]]) extends EnableBankingApi {
  @volatile var lastDateFrom: Option[LocalDate] = None

  override def getTransactions(accountUid: String, dateFrom: LocalDate, dateTo: LocalDate, continuationKey: Option[String]) = IO {
    lastDateFrom = Some(dateFrom)
    val pages = pagesByUid.getOrElse(accountUid, Nil)
    val idx   = continuationKey.map(_.toInt).getOrElse(0)
    Right(pages.lift(idx).getOrElse(EbTransactionsPage(Nil, None)))
  }

  override def listAspsps(country: Option[String])                                    = IO.pure(Right(Nil))
  override def startAuthorization(aspspName: String, aspspCountry: String, s: String) = IO.pure(Right(""))
  override def createSession(code: String)                                            = IO.pure(Right(EnableBankingClient.EbSession("s", Nil, None)))
  override def getBalances(accountUid: String)                                        = IO.pure(Left("n/a"))
  override def getAccountDetails(accountUid: String)                                  = IO.pure(Left("n/a"))
  override def deleteSession(sessionId: String)                                       = IO.pure(Right(()))
}

class TransactionImportServiceSpec extends RepositorySpec {

  private val now = Instant.parse("2026-01-15T10:00:00Z")

  /** Build a paged sequence: each page's continuationKey points at the next page's index; the last has None. */
  private def paged(txLists: List[List[EbTransaction]]): List[EbTransactionsPage] =
    txLists.zipWithIndex.map { case (txs, i) =>
      EbTransactionsPage(txs, if i < txLists.size - 1 then Some((i + 1).toString) else None)
    }

  private def ebTx(ref: Option[String], cents: Long, bookedAt: Instant, name: String = "Shop", remittance: String = "note"): EbTransaction =
    EbTransaction(ref, cents, "PLN", "booked", Some(bookedAt), Some(name), None, Some(remittance), None, Json.obj())

  /** Fresh service + repos with an active connection "c-1" and one Unlinked link for "uid-1". */
  private def setup(fake: EnableBankingApi): IO[(TransactionImportService, Repositories)] = {
    val repos = Repositories.fromTransactor(xa)
    val conn  = BankConnection(BankConnectionId("c-1"), "PKO", "PL", Some("sess"), ConnectionStatus.Active, None, None, now)
    val link  =
      BankAccountLink(
        BankAccountLinkId("l-1"),
        BankConnectionId("c-1"),
        "uid-1",
        BankLinkTarget.Unlinked,
        None,
        None,
        Some(Currency.PLN),
        None,
        None,
        None,
        None,
      )
    for {
      _ <- repos.bankConnections.create(conn)
      _ <- repos.bankConnections.createLink(link)
    } yield (new TransactionImportService(repos, Some(fake), new RuleEngineService(repos)), repos)
  }

  "imports every page following the continuation key, deduped" in {
    val fake = new PagingFakeEb(
      Map(
        "uid-1" -> paged(
          List(
            List(ebTx(Some("r1"), -1000, now), ebTx(Some("r2"), -2000, now)),
            List(ebTx(Some("r3"), 3000, now)),
          ),
        ),
      ),
    )
    for {
      (svc, repos) <- setup(fake)
      res          <- svc.importTransactions(BankConnectionId("c-1"), ImportTransactionsRequest(None))
      stored       <- repos.bankTransactions.list(Some("uid-1"), None, None)
    } yield {
      res.map(_.totalImported) shouldBe Right(3)
      stored.size shouldBe 3
    }
  }

  "re-importing the same transactions is a no-op (idempotent)" in {
    val fake = new PagingFakeEb(Map("uid-1" -> paged(List(List(ebTx(Some("r1"), -1000, now), ebTx(Some("r2"), -2000, now))))))
    for {
      (svc, repos) <- setup(fake)
      _            <- svc.importTransactions(BankConnectionId("c-1"), ImportTransactionsRequest(None))
      second       <- svc.importTransactions(BankConnectionId("c-1"), ImportTransactionsRequest(None))
      stored       <- repos.bankTransactions.list(Some("uid-1"), None, None)
    } yield {
      second.map(_.totalImported) shouldBe Right(0)
      second.map(_.totalSkipped) shouldBe Right(2)
      stored.size shouldBe 2
    }
  }

  "backfill uses a window of exactly monthsBack months" in {
    val fake = new PagingFakeEb(Map("uid-1" -> paged(List(Nil))))
    for {
      (svc, _) <- setup(fake)
      _        <- svc.importTransactions(BankConnectionId("c-1"), ImportTransactionsRequest(Some(6)))
    } yield fake.lastDateFrom shouldBe Some(LocalDate.now(ZoneOffset.UTC).minusMonths(6))
  }

  "incremental import starts a few days before the newest stored transaction" in {
    val fake     = new PagingFakeEb(Map("uid-1" -> paged(List(Nil))))
    val lastSeen = Instant.parse("2026-01-10T00:00:00Z")
    for {
      (svc, repos) <- setup(fake)
      _            <- repos.bankTransactions.insertNew(
                        BankTransaction(
                          BankTransactionId("existing"),
                          BankConnectionId("c-1"),
                          "uid-1",
                          Some("r-old"),
                          "r-old",
                          -500,
                          Currency.PLN,
                          TransactionStatus.Booked,
                          lastSeen,
                          None,
                          None,
                          None,
                          None,
                          None,
                          "{}",
                          now,
                          internal = false,
                          categorySource = None,
                        ),
                      )
      _            <- svc.importTransactions(BankConnectionId("c-1"), ImportTransactionsRequest(None))
    } yield fake.lastDateFrom shouldBe Some(LocalDate.parse("2026-01-07")) // 2026-01-10 minus 3-day overlap
  }

  "flags transfers to an own-account IBAN as internal after import" in {
    val fake = new PagingFakeEb(
      Map(
        "uid-1" -> paged(
          List(
            List(
              ebTx(Some("r-int"), -1000, now).copy(counterpartyAccount = Some("PL99 0000 1234")), // own account
              ebTx(Some("r-ext"), -2000, now).copy(counterpartyAccount = Some("DE11 2222 3333")), // third party
            ),
          ),
        ),
      ),
    )
    for {
      (svc, repos) <- setup(fake)
      // Another own account whose IBAN matches the internal transaction's counterparty.
      _            <- repos.bankConnections.createLink(
                        BankAccountLink(
                          BankAccountLinkId("l-own"),
                          BankConnectionId("c-1"),
                          "uid-own",
                          BankLinkTarget.Unlinked,
                          Some("PL9900001234"),
                          None,
                          None,
                          None,
                          None,
                          None,
                          None,
                        ),
                      )
      _            <- svc.importTransactions(BankConnectionId("c-1"), ImportTransactionsRequest(None))
      stored       <- repos.bankTransactions.list(Some("uid-1"), None, None)
    } yield {
      stored.find(_.entryReference.contains("r-int")).map(_.internal) shouldBe Some(true)
      stored.find(_.entryReference.contains("r-ext")).map(_.internal) shouldBe Some(false)
    }
  }

  "transactions without an entry_reference are deduped by a synthetic key" in {
    val a    = ebTx(None, -1234, now, name = "Cafe", remittance = "coffee")
    val fake = new PagingFakeEb(Map("uid-1" -> paged(List(List(a, a))))) // identical, no entry_reference
    for {
      (svc, repos) <- setup(fake)
      res          <- svc.importTransactions(BankConnectionId("c-1"), ImportTransactionsRequest(None))
      stored       <- repos.bankTransactions.list(Some("uid-1"), None, None)
    } yield {
      res.map(_.totalImported) shouldBe Right(1)
      stored.size shouldBe 1
    }
  }
}
