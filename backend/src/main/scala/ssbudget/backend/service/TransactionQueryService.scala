package ssbudget.backend.service

import cats.effect.IO
import ssbudget.backend.db.Repositories
import ssbudget.backend.db.repository.{TransactionFilter, TransactionSearchRow}
import ssbudget.backend.search.TransactionSearch
import ssbudget.backend.service.BudgetQueries.previousClosedPeriod
import ssbudget.shared.api.*
import ssbudget.shared.model.*

import java.time.Instant

/** The transaction list of the Transactions page: the filters, the free-text search, and the note of a transaction. */
class TransactionQueryService(repos: Repositories) {

  /** Cap the number of rows shipped to the browser; the response carries the true total so the UI can prompt the user to narrow filters. */
  private val transactionPageCap = 500

  def list(
      accountUid: Option[String],
      month: Option[String],
      category: Option[String],
      hideInternal: Option[Boolean],
      sort: Option[String],
      asc: Option[Boolean],
      limit: Option[Int],
      q: Option[String],
  ): IO[TransactionListResponse] = {
    // The month dropdown carries period sentinels instead of a YYYY-MM bucket; resolve them to the same [from, to) window the category-spend figures
    // use (see `PeriodWindow`), so drilling into a category budget lists exactly the transactions its number was computed from.
    val isSentinel = month.exists(m => m == MonthFilter.CurrentPeriod || m == MonthFilter.PreviousPeriod)
    val searchTerm = q.map(_.trim).filter(_.nonEmpty)
    val cap        = limit.getOrElse(transactionPageCap)
    val sortKey    = sort.getOrElse("date")
    val ascending  = asc.getOrElse(false)
    for {
      periodOpt <- month match {
                     case Some(MonthFilter.CurrentPeriod)  => repos.periods.findCurrent
                     case Some(MonthFilter.PreviousPeriod) => repos.periods.findAll.map(previousClosedPeriod)
                     case _                                => IO.pure(Option.empty[Period])
                   }
      window     = periodOpt.map(PeriodWindow.of)
      filter     = TransactionFilter(
                     accountUid = accountUid.filter(_.nonEmpty),
                     month = if isSentinel then None else month.filter(_.nonEmpty),
                     from = window.map(_.from),
                     to = window.flatMap(_.until),
                     category = category.filter(_.nonEmpty),
                     hideInternal = hideInternal.getOrElse(false),
                   )
      // A sentinel that resolves to nothing (no period started, or no closed period yet) means "no such window", not "no filter".
      res       <- if isSentinel && periodOpt.isEmpty then IO.pure(TransactionListResponse(Nil, 0, Nil))
                   else
                     searchTerm match {
                       case Some(term) => search(filter, sortKey, ascending, cap, term)
                       case None       =>
                         repos.bankTransactions.query(filter, sortKey, ascending, Some(cap)).map { case (items, total, sums) =>
                           TransactionListResponse(items, total, sums.map { case (cur, cents) => Money(cents, cur) })
                         }
                     }
    } yield res
  }

  /** The free-text path. The match is diacritic-folded and typo-tolerant, neither of which SQL can express, so it runs in memory: pull every row the
    * other filters admit as a lightweight projection, score it, and only then fetch the full transactions for the page that survives. At this data
    * size (single-digit thousands of rows, ~60 characters of text each) the scan is a couple of milliseconds — far cheaper than the denormalised
    * column and sync triggers that would be needed to push it into SQLite.
    *
    * Exact and near matches are ranked and capped independently, so a flood of approximate hits can never push the literal ones off the page.
    */
  private def search(
      filter: TransactionFilter,
      sort: String,
      asc: Boolean,
      cap: Int,
      term: String,
  ): IO[TransactionListResponse] = {
    val parsed = TransactionSearch.parse(term)
    for {
      candidates   <- repos.bankTransactions.searchCandidates(filter)
      scored        = candidates.flatMap { row =>
                        TransactionSearch.tierOf(parsed, TransactionSearch.fold(row.text), Math.abs(row.amountCents)).map(row -> _)
                      }
      (exact, near) = scored.partition(_._2 == TransactionSearch.MatchTier.Exact)
      // Mirrors the ORDER BY the SQL path uses. `booked_at` is stored as an ISO-8601 string, so SQLite compares it lexicographically; that agrees
      // with comparing the instants for the day-precision timestamps banks actually send, and would only part company on a sub-second one.
      ordering      = if sort == "amount" then Ordering.by[TransactionSearchRow, Long](_.amountCents)
                      else Ordering.by[TransactionSearchRow, Instant](_.bookedAt)
      order         = if asc then ordering else ordering.reverse
      exactRows     = exact.map(_._1).sorted(using order)
      // The cap is a display budget for the whole page: exact matches claim it first, and near matches get whatever is left. When they get nothing,
      // only the count of them is wanted, and a count doesn't need an order.
      shownExact    = exactRows.take(cap)
      nearBudget    = cap - shownExact.size
      nearRows      = near.map(_._1)
      shownNear     = if nearBudget == 0 then Nil else nearRows.sorted(using order).take(nearBudget)
      full         <- repos.bankTransactions.findByIds((shownExact ++ shownNear).map(_.id))
      byId          = full.map(t => t.id -> t).toMap
      // Totals cover the exact tier only, and the FULL exact match rather than the capped page.
      sums          = exactRows.groupBy(_.currency).toList.map { case (cur, rows) => Money(rows.map(_.amountCents).sum, cur) }
    } yield TransactionListResponse(
      items = shownExact.flatMap(r => byId.get(r.id)),
      total = exactRows.size,
      sums = sums,
      near = shownNear.flatMap(r => byId.get(r.id)),
    )
  }

  def setNote(id: BankTransactionId, dto: SetNoteRequest): IO[Either[ApiError, BankTransaction]] =
    for {
      txOpt  <- repos.bankTransactions.findById(id)
      result <- txOpt match {
                  case None     => IO.pure(Left(ApiError.notFound(s"Transaction not found: ${id.value}")))
                  case Some(tx) =>
                    val cleaned = dto.note.map(_.trim).filter(_.nonEmpty) // blank note clears it
                    repos.bankTransactions.setNote(id, cleaned).as(Right(tx.copy(note = cleaned)))
                }
    } yield result
}
