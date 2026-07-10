package ssbudget.backend.banking

import cats.effect.IO
import cats.implicits.*
import org.slf4j.LoggerFactory
import ssbudget.backend.banking.EnableBankingClient.EbTransaction
import ssbudget.backend.db.Repositories
import ssbudget.shared.api.*
import ssbudget.shared.model.*

import java.security.MessageDigest
import java.time.{Instant, LocalDate, ZoneOffset}
import java.util.UUID

/** Imports bank transactions from Enable Banking with manageable backfill.
  *
  *   - Incremental (`monthsBack = None`): per account, fetch from a few days before the newest transaction we already have (overlap absorbs
  *     late-posted items; dedup makes it safe), or a short initial window if we have none yet.
  *   - Backfill (`monthsBack = Some(n)`): fetch the last n months.
  *
  * Idempotent: every transaction is deduped by `(ebAccountUid, dedupKey)`, so overlapping/repeated runs never create duplicates.
  */
class TransactionImportService(repos: Repositories, clientOpt: Option[EnableBankingApi], ruleEngine: RuleEngineService) {

  private val log = LoggerFactory.getLogger(classOf[TransactionImportService])

  private val notConfigured      = "Enable Banking integration is not configured"
  private val overlapDays        = 3L  // re-scan a few days back so late-posted transactions aren't missed
  private val defaultInitialDays = 30L // first-ever import window when we have no transactions for an account
  private val maxPages           = 50  // safety cap on continuation-key pagination

  def importTransactions(connectionId: BankConnectionId, req: ImportTransactionsRequest): IO[Either[String, ImportResult]] =
    clientOpt match {
      case None         => IO.pure(Left(notConfigured))
      case Some(client) =>
        repos.bankConnections.findById(connectionId).flatMap {
          case None                                 => IO.pure(Left("Connection not found"))
          case Some(conn) if conn.sessionId.isEmpty => IO.pure(Left("Connection is not authorized yet"))
          case Some(_)                              =>
            for {
              now     <- IO.realTimeInstant
              today    = LocalDate.ofInstant(now, ZoneOffset.UTC)
              links   <- repos.bankConnections.findLinksByConnection(connectionId)
              scope    = req.monthsBack.fold("incremental (new only)")(n => s"backfill last $n month(s)")
              _       <- IO(log.info(s"[import] connection ${connectionId.value}: $scope over ${links.size} account(s)"))
              results <- links.traverse(link => importAccount(client, connectionId, link, req.monthsBack, today, now))
              _       <- repos.bankTransactions.markInternalTransfers() // built-in rule: flag own-account transfers
              _       <- ruleEngine.applyRules()                        // then auto-categorize via user-defined rules
              _       <-
                IO(
                  log.info(
                    s"[import] connection ${connectionId.value}: done — ${results.map(_.imported).sum} imported, ${results.map(_.skipped).sum} skipped",
                  ),
                )
            } yield Right(ImportResult(results))
        }
    }

  private def importAccount(
      client: EnableBankingApi,
      connId: BankConnectionId,
      link: BankAccountLink,
      monthsBack: Option[Int],
      today: LocalDate,
      now: Instant,
  ): IO[AccountImportResult] =
    for {
      dateFrom <- monthsBack match {
                    case Some(n) => IO.pure(today.minusMonths(n.toLong))
                    case None    =>
                      repos.bankTransactions.latestBookedAt(link.ebAccountUid).map {
                        case Some(last) => LocalDate.ofInstant(last, ZoneOffset.UTC).minusDays(overlapDays)
                        case None       => today.minusDays(defaultInitialDays)
                      }
                  }
      _        <- IO(log.info(s"[import] ${link.ebAccountUid}: fetching $dateFrom..$today"))
      counts   <- importPages(client, connId, link, dateFrom, today, None, now, page = 0, imported = 0, skipped = 0)
      _        <- IO(log.info(s"[import] ${link.ebAccountUid}: imported ${counts._1}, skipped ${counts._2}"))
    } yield AccountImportResult(link.ebAccountUid, counts._1, counts._2)

  private def importPages(
      client: EnableBankingApi,
      connId: BankConnectionId,
      link: BankAccountLink,
      from: LocalDate,
      to: LocalDate,
      continuationKey: Option[String],
      now: Instant,
      page: Int,
      imported: Int,
      skipped: Int,
  ): IO[(Int, Int)] =
    if page >= maxPages then IO(log.warn(s"[import] ${link.ebAccountUid}: hit $maxPages-page cap, stopping")).as((imported, skipped))
    else
      client.getTransactions(link.ebAccountUid, from, to, continuationKey).flatMap {
        case Left(err)    =>
          // Best-effort: stop this account, keep what we imported, but surface why.
          IO(log.warn(s"[import] ${link.ebAccountUid}: fetch failed on page ${page + 1}: $err")).as((imported, skipped))
        case Right(page0) =>
          for {
            _          <- IO(log.info(s"[import] ${link.ebAccountUid}: page ${page + 1} → ${page0.transactions.size} tx"))
            inserted   <- page0.transactions.traverse(t => insertOne(connId, link, t, now))
            newImported = imported + inserted.count(identity)
            newSkipped  = skipped + inserted.count(!_)
            result     <- page0.continuationKey match {
                            case Some(key) => importPages(client, connId, link, from, to, Some(key), now, page + 1, newImported, newSkipped)
                            case None      => IO.pure((newImported, newSkipped))
                          }
          } yield result
      }

  private def insertOne(connId: BankConnectionId, link: BankAccountLink, t: EbTransaction, now: Instant): IO[Boolean] = {
    val bookedAt = t.bookedAt.getOrElse(now)
    val currency = if t.currency.nonEmpty then Currency(t.currency) else link.currency.getOrElse(Currency.PLN)
    val dedupKey = t.entryReference.getOrElse(syntheticKey(link.ebAccountUid, bookedAt, t.amountCents, t.counterpartyName, t.remittance))
    val tx       = BankTransaction(
      BankTransactionId(UUID.randomUUID().toString),
      connId,
      link.ebAccountUid,
      t.entryReference,
      dedupKey,
      t.amountCents,
      currency,
      if t.status == "pending" then TransactionStatus.Pending else TransactionStatus.Booked,
      bookedAt,
      t.counterpartyName,
      t.counterpartyAccount,
      t.remittance,
      t.bankTransactionCode,
      categoryId = None,
      rawJson = t.raw.noSpaces,
      importedAt = now,
      internal = false,     // set by markInternalTransfers after the import completes
      categorySource = None, // set by the rule engine after the import completes
    )
    repos.bankTransactions.insertNew(tx)
  }

  /** Stable dedup key when the bank gives no `entry_reference`: hash of the salient fields. */
  private def syntheticKey(uid: String, bookedAt: Instant, amountCents: Long, name: Option[String], remittance: Option[String]): String = {
    val canonical = List(uid, bookedAt.toString, amountCents.toString, name.getOrElse(""), remittance.getOrElse("")).mkString("|")
    val digest    = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes("UTF-8"))
    "syn-" + digest.take(16).map("%02x".format(_)).mkString
  }
}
