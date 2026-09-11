package ssbudget.backend.search

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import ssbudget.backend.search.TransactionSearch.MatchTier

class TransactionSearchSpec extends AnyFreeSpec with Matchers {

  private def tier(query: String, text: String, amountCents: Long = 0L): Option[MatchTier] =
    TransactionSearch.tierOf(TransactionSearch.parse(query), TransactionSearch.fold(text), Math.abs(amountCents))

  "fold" - {
    "strips combining diacritics" in {
      TransactionSearch.fold("Świadczeń") shouldBe "swiadczen"
      TransactionSearch.fold("KAROLINA GALANT") shouldBe "karolina galant"
    }

    // The regression this whole map exists for: `ł` is a letter in its own right, not a base letter plus a combining mark, so Unicode decomposition
    // leaves it alone — as does SQLite's `remove_diacritics`. Without the explicit mapping a quarter of this database becomes unsearchable.
    "maps letters that decomposition leaves alone" in {
      TransactionSearch.fold("WOJCIECH PITUŁA") shouldBe "wojciech pitula"
      TransactionSearch.fold("MAŁGORZATA") shouldBe "malgorzata"
      TransactionSearch.fold("Ø đ ß") shouldBe "o d ss"
    }

    "leaves plain ASCII alone but lowercased" in {
      TransactionSearch.fold("Card transaction of 5.00 USD") shouldBe "card transaction of 5.00 usd"
    }
  }

  "exact matching" - {
    "matches a substring inside a longer token" in {
      tier("gpt", "CARD Card transaction issued by Openai *Chatgpt Subscr") shouldBe Some(MatchTier.Exact)
    }

    "matches across a diacritic" in {
      tier("pitula", "WOJCIECH PITUŁA USŁUGI") shouldBe Some(MatchTier.Exact)
    }

    "ANDs the terms, so extra words narrow rather than widen" in {
      tier("wise card", "FEE-CARD-2673031953 Wise Charges for: CARD-2673031953") shouldBe Some(MatchTier.Exact)
      tier("wise revolut", "FEE-CARD-2673031953 Wise Charges for: CARD-2673031953") shouldBe None
    }

    "matches an amount-shaped term against the transaction's amount" in {
      tier("37.99", "Paypal *Spotify*P389aa643", amountCents = -3799) shouldBe Some(MatchTier.Exact)
      tier("37,99", "Paypal *Spotify*P389aa643", amountCents = -3799) shouldBe Some(MatchTier.Exact)
      tier("37.99", "Paypal *Spotify*P389aa643", amountCents = -1000) shouldBe None
    }

    "an empty query matches everything" in {
      tier("", "anything at all") shouldBe Some(MatchTier.Exact)
    }
  }

  "near matching" - {
    "tolerates a single edit per term" in {
      tier("wojcech", "WOJCIECH PITUŁA") shouldBe Some(MatchTier.Near)  // deletion
      tier("bidronka", "BIEDRONKA 123") shouldBe Some(MatchTier.Near)   // insertion
      tier("spotifz", "Paypal *Spotify*") shouldBe Some(MatchTier.Near) // substitution
    }

    "stops at two edits, which reaches unrelated merchants" in {
      tier("medum", "Medium Monthly MEDIUM.COM") shouldBe Some(MatchTier.Near)
      tier("mdum", "Medium Monthly MEDIUM.COM") shouldBe None
    }

    "will not fuzz a short term, where one edit reaches too far" in {
      tier("zus", "ZUZ something") shouldBe None
      tier("zus", "ZUS Centrum Obsługi") shouldBe Some(MatchTier.Exact)
    }

    "reports Exact when every term matched literally, even alongside a fuzzy-eligible one" in {
      tier("wise charges", "Wise Charges for: CARD-1") shouldBe Some(MatchTier.Exact)
    }

    "demotes the whole row when any one term needed the allowance" in {
      tier("wise charjes", "Wise Charges for: CARD-1") shouldBe Some(MatchTier.Near)
    }
  }

  "withinOneEdit" - {
    "accepts equality, substitution, insertion and deletion" in {
      TransactionSearch.withinOneEdit("abcd", "abcd") shouldBe true
      TransactionSearch.withinOneEdit("abcd", "abxd") shouldBe true
      TransactionSearch.withinOneEdit("abcd", "abcde") shouldBe true
      TransactionSearch.withinOneEdit("abcde", "abcd") shouldBe true
      TransactionSearch.withinOneEdit("abcd", "bcd") shouldBe true
    }

    "rejects two or more edits" in {
      TransactionSearch.withinOneEdit("abcd", "axyd") shouldBe false
      TransactionSearch.withinOneEdit("abcd", "abcdef") shouldBe false
      TransactionSearch.withinOneEdit("abcd", "dcba") shouldBe false
    }
  }
}
