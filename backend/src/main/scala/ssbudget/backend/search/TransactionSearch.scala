package ssbudget.backend.search

import cats.implicits.*

import java.text.Normalizer

/** Free-text matching for the transaction search box.
  *
  * Both sides go through [[fold]] — the row's text and the user's query — so comparison is always on lowercase ASCII and `pitula` finds `PITUŁA`.
  * Folding happens on read rather than in a stored column: the fuzzy pass has to scan every candidate in the JVM regardless, so a denormalised column
  * would buy a couple of milliseconds in exchange for a migration, a backfill and two write paths to keep in sync.
  *
  * A query matches in one of two tiers. [[MatchTier.Exact]] means every term is a literal substring (or the row's amount); [[MatchTier.Near]] means
  * every term matches but at least one only survived a typo. The two are reported separately all the way to the UI — near matches are listed under
  * their own divider and are left out of the totals row, so searching a merchant still gives a trustworthy sum.
  */
object TransactionSearch {

  /** Terms shorter than this are matched literally only. At three characters a single edit reaches too many unrelated tokens to be useful. */
  private val MinFuzzyLength = 4

  /** Characters Unicode decomposition does NOT strip, because they are letters in their own right rather than a base letter plus a combining mark.
    * `ł` is the one that matters here: about a quarter of this database's rows carry Polish names, and without this map `pitula` misses `PITUŁA`
    * entirely — NFD leaves `ł` alone, and so does SQLite's own `remove_diacritics`. The others are cheap insurance for foreign merchant names.
    * Everything else Polish (`ą ć ę ń ó ś ź ż`) decomposes normally and needs no entry here.
    */
  private val standalone: Map[Char, String] =
    Map('ł' -> "l", 'Ł' -> "l", 'ø' -> "o", 'Ø' -> "o", 'đ' -> "d", 'Đ' -> "d", 'ß' -> "ss", 'æ' -> "ae", 'Æ' -> "ae", 'œ' -> "oe", 'Œ' -> "oe")

  /** Lowercase, diacritic-free form of `s`. Pure-ASCII input — about three quarters of rows — skips the normaliser entirely, which is what keeps a
    * full-table fold cheap enough to do per request.
    */
  def fold(s: String): String = {
    if isPlainAscii(s) then s.toLowerCase
    else {
      val mapped = {
        val sb = new StringBuilder(s.length)
        s.foreach(c => standalone.get(c).fold(sb.append(c))(sb.append))
        sb.toString
      }
      val nfd    = Normalizer.normalize(mapped, Normalizer.Form.NFD)
      val sb     = new StringBuilder(nfd.length)
      nfd.foreach(c => if Character.getType(c) != Character.NON_SPACING_MARK then sb.append(c))
      sb.toString.toLowerCase
    }
  }

  private def isPlainAscii(s: String): Boolean = {
    var i = 0
    while i < s.length do {
      if s.charAt(i) > 127 then return false
      i += 1
    }
    true
  }

  /** Alphanumeric runs of a folded string — the units the fuzzy pass compares against. */
  private def tokens(folded: String): Array[String] = folded.split("[^a-z0-9]+").filter(_.nonEmpty)

  enum MatchTier {
    case Exact, Near
  }

  /** A parsed query, pre-folded once so scanning thousands of rows doesn't re-fold the same terms. Terms are ANDed: every one has to match, which is
    * what makes typing more words narrow the result rather than widen it.
    */
  final case class Query(terms: List[Term])

  /** One whitespace-separated term. [[amountCents]] is set when the term reads as a money figure (`37.99`, `3799`), letting the same box find a
    * transaction by the amount on a receipt.
    */
  final case class Term(text: String, amountCents: Option[Long]) {
    val fuzzy: Boolean = text.length >= MinFuzzyLength
  }

  def parse(raw: String): Query =
    Query(fold(raw).split("\\s+").filter(_.nonEmpty).map(t => Term(t, amountOf(t))).toList)

  /** `37.99` / `37,99` / `3799` as cents. Only used to widen a match, never to narrow one, so a loose reading is safe. */
  private def amountOf(term: String): Option[Long] =
    Option
      .when(term.matches("\\d+([.,]\\d{1,2})?"))(term.replace(',', '.'))
      .flatMap(_.toDoubleOption)
      .map(d => Math.round(d * 100))

  /** Which tier `text` matches `query` in, or `None` when it doesn't match at all. `absAmountCents` is the row's amount, matched literally by an
    * amount-shaped term.
    *
    * A row is [[MatchTier.Near]] only when every term matches and at least one needed a typo allowance — so a clean query never gets its exact
    * results diluted by approximate ones.
    */
  def tierOf(query: Query, text: String, absAmountCents: Long): Option[MatchTier] = {
    lazy val toks                            = tokens(text)
    // Some(false) = matched literally, Some(true) = only survived a typo, None = this term misses. `traverse` short-circuits on the first miss, and
    // over no terms at all it yields an empty list — so a blank query falls out as Exact without a special case.
    def matchOf(term: Term): Option[Boolean] = {
      if text.contains(term.text) || term.amountCents.contains(absAmountCents) then Some(false)
      else if term.fuzzy && toks.exists(withinOneEdit(term.text, _)) then Some(true)
      else None
    }
    query.terms.traverse(matchOf).map(fuzzied => if fuzzied.contains(true) then MatchTier.Near else MatchTier.Exact)
  }

  /** True when `a` and `b` are equal or one edit apart (substitution, insertion or deletion).
    *
    * A distance of one is deliberate: measured against this database, it catches the realistic slips (`wojcech`, `bidronka`, `audble`) while leaving
    * clean queries untouched, whereas a distance of two starts pulling in unrelated merchants.
    */
  def withinOneEdit(a: String, b: String): Boolean = {
    val (shorter, longer) = if a.length <= b.length then (a, b) else (b, a)
    longer.length - shorter.length match {
      case 0 =>
        var diffs = 0
        var i     = 0
        while i < longer.length && diffs <= 1 do {
          if longer.charAt(i) != shorter.charAt(i) then diffs += 1
          i += 1
        }
        diffs <= 1
      case 1 =>
        // Walk both in step; on the first mismatch, skip one character of the longer string and require the rest to line up exactly.
        var i       = 0
        var skipped = false
        var j       = 0
        while j < longer.length do {
          if i < shorter.length && shorter.charAt(i) == longer.charAt(j) then i += 1
          else if skipped then return false
          else skipped = true
          j += 1
        }
        true
      case _ => false
    }
  }
}
