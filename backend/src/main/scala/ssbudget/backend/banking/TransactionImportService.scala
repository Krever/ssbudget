package ssbudget.backend.banking

import cats.effect.{IO, Ref}
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
/** What became of one entry of the bank. */
private enum Outcome {
  case Imported, Known, Pending, Unreadable
}

class TransactionImportService(repos: Repositories, clientOpt: Option[EnableBankingApi], ruleEngine: RuleEngineService) {

  private val log = LoggerFactory.getLogger(classOf[TransactionImportService])

  private val notConfigured      = "Enable Banking integration is not configured"
  private val overlapDays        = 3L  // re-scan a few days back so late-posted transactions aren't missed
  private val defaultInitialDays = 30L // first-ever import window when we have no transactions for an account
  private val maxPages           = 50  // safety cap on continuation-key pagination

  /** `onAccountStart(link)` fires before each account is imported and `onAccountDone(link, result)` after — so a caller (the job runner) can surface
    * per-account progress, running counts and an incomplete fetch. Both default to no-ops.
    */
  def importTransactions(
      connectionId: BankConnectionId,
      req: ImportTransactionsRequest,
      onAccountStart: BankAccountLink => IO[Unit] = _ => IO.unit,
      onAccountDone: (BankAccountLink, AccountImportResult) => IO[Unit] = (_, _) => IO.unit,
  ): IO[Either[String, ImportResult]] =
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
              results <- links.traverse { link =>
                           onAccountStart(link) *>
                             importAccount(client, connectionId, link, req.monthsBack, today, now)
                               .flatTap(r => onAccountDone(link, r))
                         }
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
      dateFrom   <- monthsBack match {
                      case Some(n) => IO.pure(today.minusMonths(n.toLong))
                      case None    =>
                        repos.bankTransactions.latestBookedAt(link.ebAccountUid).map {
                          case Some(last) => LocalDate.ofInstant(last, ZoneOffset.UTC).minusDays(overlapDays)
                          case None       => today.minusDays(defaultInitialDays)
                        }
                    }
      _          <- IO(log.info(s"[import] ${link.ebAccountUid}: fetching $dateFrom..$today"))
      unreadable <- IO.ref(0)
      fetched    <- importPages(client, connId, link, dateFrom, today, None, now, page = 0, imported = 0, skipped = 0, unreadable)
      leftOut    <- unreadable.get
      // An entry that was left out is a fault that the user must see: the figures of the account miss it.
      note        = Option.when(leftOut > 0)(s"$leftOut entr${if leftOut == 1 then "y" else "ies"} with no readable amount or date left out")
      result      = fetched.copy(error = (fetched.error.toList ++ note.toList).reduceOption(_ + "; " + _))
      _          <- IO(log.info(s"[import] ${link.ebAccountUid}: imported ${result.imported}, skipped ${result.skipped}"))
    } yield result

  /** Pages arrive newest first, so a fetch that stops early keeps the newest transactions and loses the older ones. The next incremental import
    * starts from the newest stored transaction and does not fetch them again. Thus an early stop must be reported: only a backfill repairs it.
    */
  private def incomplete(link: BankAccountLink, from: LocalDate, pagesDone: Int, imported: Int, skipped: Int, reason: String): AccountImportResult =
    AccountImportResult(
      link.ebAccountUid,
      imported,
      skipped,
      Some(
        s"history incomplete — stopped after $pagesDone page(s) of the window from $from: $reason. Run a backfill to get the missing transactions.",
      ),
    )

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
      unreadableSeen: Ref[IO, Int],
  ): IO[AccountImportResult] =
    if page >= maxPages then IO(log.warn(s"[import] ${link.ebAccountUid}: hit $maxPages-page cap, stopping"))
      .as(incomplete(link, from, page, imported, skipped, s"reached the limit of $maxPages pages"))
    else
      client.getTransactions(link.ebAccountUid, from, to, continuationKey).flatMap {
        case Left(err)    =>
          // Best-effort: stop this account and keep what we imported, but report the failure to the caller.
          BankingService.expireIfSessionEnded(repos, connId, err) *>
            IO(log.warn(s"[import] ${link.ebAccountUid}: fetch failed on page ${page + 1}: $err"))
              .as(incomplete(link, from, page, imported, skipped, err))
        case Right(page0) =>
          for {
            _          <- IO(log.info(s"[import] ${link.ebAccountUid}: page ${page + 1} → ${page0.transactions.size} tx"))
            inserted   <- page0.transactions.traverse(t => insertOne(connId, link, t, now))
            newImported = imported + inserted.count(_ == Outcome.Imported)
            // A pending entry comes again as a booked one, so it is skipped as a known entry is.
            newSkipped  = skipped + inserted.count(o => o == Outcome.Known || o == Outcome.Pending)
            unreadable  = inserted.count(_ == Outcome.Unreadable)
            _          <- IO.whenA(unreadable > 0)(unreadableSeen.update(_ + unreadable))
            result     <- page0.continuationKey match {
                            case Some(key) =>
                              importPages(client, connId, link, from, to, Some(key), now, page + 1, newImported, newSkipped, unreadableSeen)
                            case None      => IO.pure(AccountImportResult(link.ebAccountUid, newImported, newSkipped))
                          }
          } yield result
      }

  /** Stores one entry of the bank, when it can be used.
    *
    * Two kinds of entry are left out. A pending entry is not final: its amount can change, and the bank sends it again as a booked entry, often with
    * another reference, so to store both would count the money two times. An entry with no amount or no date that can be read has no honest value: an
    * invented zero or an invented "today" would be a wrong figure, and a new date on each import would also make a new row each time. Both kinds are
    * counted, so a sync says that it left something out.
    */
  private def insertOne(connId: BankConnectionId, link: BankAccountLink, t: EbTransaction, now: Instant): IO[Outcome] =
    (t.amountCents, t.bookedAt) match {
      case _ if t.status == "pending"          => IO.pure(Outcome.Pending)
      case (Some(amountCents), Some(bookedAt)) =>
        // A bank that names no currency for an entry means the currency of the account.
        val currency = if t.currency.nonEmpty then Currency(t.currency) else link.currency.getOrElse(Currency.PLN)
        val dedupKey = t.entryReference.getOrElse(syntheticKey(link.ebAccountUid, bookedAt, amountCents, t.counterpartyName, t.remittance))
        val tx       = BankTransaction(
          BankTransactionId(UUID.randomUUID().toString),
          connId,
          link.ebAccountUid,
          t.entryReference,
          dedupKey,
          amountCents,
          currency,
          TransactionStatus.Booked,
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
        repos.bankTransactions.insertNew(tx).map(if _ then Outcome.Imported else Outcome.Known)
      case _                                   =>
        IO(log.warn(s"[import] ${link.ebAccountUid}: an entry with no readable amount or date was left out: ${t.raw.noSpaces.take(300)}"))
          .as(Outcome.Unreadable)
    }

  /** Stable dedup key when the bank gives no `entry_reference`: hash of the salient fields. */
  private def syntheticKey(uid: String, bookedAt: Instant, amountCents: Long, name: Option[String], remittance: Option[String]): String = {
    val canonical = List(uid, bookedAt.toString, amountCents.toString, name.getOrElse(""), remittance.getOrElse("")).mkString("|")
    val digest    = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes("UTF-8"))
    "syn-" + digest.take(16).map("%02x".format(_)).mkString
  }
}
