package ssbudget.backend.db.repository

import ssbudget.shared.model.*

import java.time.Instant

class BankTransactionRepositorySpec extends RepositorySpec {

  private val importedAt = Instant.parse("2026-01-15T10:00:00Z")

  private def tx(
      id: String,
      uid: String,
      dedupKey: String,
      amountCents: Long = -1000,
      bookedAt: Instant = Instant.parse("2026-01-10T00:00:00Z"),
      connId: String = "conn-1",
      categoryId: Option[CategoryId] = None,
      counterpartyAccount: Option[String] = None,
      remittance: Option[String] = Some("note"),
      categorySource: Option[CategorySource] = None,
  ): BankTransaction =
    BankTransaction(
      BankTransactionId(id),
      BankConnectionId(connId),
      uid,
      entryReference = Some(dedupKey),
      dedupKey = dedupKey,
      amountCents = amountCents,
      currency = Currency.PLN,
      status = TransactionStatus.Booked,
      bookedAt = bookedAt,
      counterpartyName = Some("Shop"),
      counterpartyAccount = counterpartyAccount,
      remittance = remittance,
      bankTransactionCode = None,
      categoryId = categoryId,
      rawJson = "{}",
      importedAt = importedAt,
      internal = false,
      categorySource = categorySource,
    )

  "insertNew inserts once and dedups on (ebAccountUid, dedupKey)" in {
    val repo = new BankTransactionRepositoryImpl(xa)
    for {
      first  <- repo.insertNew(tx("t-1", "uid-1", "ref-1"))
      second <- repo.insertNew(tx("t-2", "uid-1", "ref-1")) // same account + dedupKey → skipped
      all    <- repo.list(Some("uid-1"), None, None)
    } yield {
      first shouldBe true
      second shouldBe false
      all.map(_.id.value) shouldBe List("t-1")
    }
  }

  "same dedupKey on a different account is not a duplicate" in {
    val repo = new BankTransactionRepositoryImpl(xa)
    for {
      a   <- repo.insertNew(tx("t-1", "uid-1", "ref-1"))
      b   <- repo.insertNew(tx("t-2", "uid-2", "ref-1"))
      all <- repo.list(None, None, None)
    } yield {
      a shouldBe true
      b shouldBe true
      all.map(_.id.value).toSet shouldBe Set("t-1", "t-2")
    }
  }

  "list filters by account and date range, ordered by booked_at desc" in {
    val repo = new BankTransactionRepositoryImpl(xa)
    val jan  = Instant.parse("2026-01-05T00:00:00Z")
    val feb  = Instant.parse("2026-02-05T00:00:00Z")
    val mar  = Instant.parse("2026-03-05T00:00:00Z")
    for {
      _       <- repo.insertNew(tx("t-jan", "uid-1", "r-jan", bookedAt = jan))
      _       <- repo.insertNew(tx("t-feb", "uid-1", "r-feb", bookedAt = feb))
      _       <- repo.insertNew(tx("t-mar", "uid-2", "r-mar", bookedAt = mar))
      byAcct  <- repo.list(Some("uid-1"), None, None)
      byRange <- repo.list(None, Some(Instant.parse("2026-02-01T00:00:00Z")), Some(Instant.parse("2026-03-31T00:00:00Z")))
    } yield {
      byAcct.map(_.id.value) shouldBe List("t-feb", "t-jan") // desc
      byRange.map(_.id.value).toSet shouldBe Set("t-feb", "t-mar")
    }
  }

  "latestBookedAt returns the newest booking date for an account, or None" in {
    val repo   = new BankTransactionRepositoryImpl(xa)
    val old    = Instant.parse("2026-01-01T00:00:00Z")
    val recent = Instant.parse("2026-03-01T00:00:00Z")
    for {
      _       <- repo.insertNew(tx("t-1", "uid-1", "r-1", bookedAt = old))
      _       <- repo.insertNew(tx("t-2", "uid-1", "r-2", bookedAt = recent))
      latest  <- repo.latestBookedAt("uid-1")
      missing <- repo.latestBookedAt("uid-none")
    } yield {
      latest shouldBe Some(recent)
      missing shouldBe None
    }
  }

  "setCategory assigns as manual and clearCategory detaches" in {
    val repo    = new BankTransactionRepositoryImpl(xa)
    val catRepo = new CategoryRepositoryImpl(xa)
    for {
      _        <- catRepo.create(Category(CategoryId("cat-1"), "Groceries", None))
      _        <- repo.insertNew(tx("t-1", "uid-1", "r-1"))
      _        <- repo.setCategory(BankTransactionId("t-1"), Some(CategoryId("cat-1")))
      assigned <- repo.findById(BankTransactionId("t-1"))
      _        <- repo.clearCategory(CategoryId("cat-1"))
      cleared  <- repo.findById(BankTransactionId("t-1"))
    } yield {
      assigned.flatMap(_.categoryId) shouldBe Some(CategoryId("cat-1"))
      assigned.flatMap(_.categorySource) shouldBe Some(CategorySource.Manual)
      cleared.flatMap(_.categoryId) shouldBe None
      cleared.flatMap(_.categorySource) shouldBe None
    }
  }

  "applyCategoryUpdates batch-updates category and source" in {
    val repo    = new BankTransactionRepositoryImpl(xa)
    val catRepo = new CategoryRepositoryImpl(xa)
    for {
      _   <- catRepo.create(Category(CategoryId("cat-1"), "Groceries", None))
      _   <- repo.insertNew(tx("t-1", "uid-1", "r-1"))
      _   <- repo.insertNew(tx("t-2", "uid-1", "r-2"))
      _   <- repo.applyCategoryUpdates(
               List(
                 (BankTransactionId("t-1"), Some(CategoryId("cat-1")), Some(CategorySource.Rule)),
                 (BankTransactionId("t-2"), None, None),
               ),
             )
      one <- repo.findById(BankTransactionId("t-1"))
      two <- repo.findById(BankTransactionId("t-2"))
    } yield {
      one.flatMap(_.categoryId) shouldBe Some(CategoryId("cat-1"))
      one.flatMap(_.categorySource) shouldBe Some(CategorySource.Rule)
      two.flatMap(_.categoryId) shouldBe None
    }
  }

  "markInternalTransfers flags only transactions whose counterparty is an own-account IBAN (case/space-insensitive)" in {
    val repo     = new BankTransactionRepositoryImpl(xa)
    val connRepo = new BankConnectionRepositoryImpl(xa)
    val ownLink  = BankAccountLink(
      BankAccountLinkId("l-own"),
      BankConnectionId("conn-1"),
      "uid-own",
      BankLinkTarget.Unlinked,
      iban = Some("PL10 2040 0000"),
      name = None,
      currency = None,
      product = None,
      lastBalanceCents = None,
      lastBalanceCurrency = None,
      lastSyncedAt = None,
    )
    for {
      _   <- connRepo.create(BankConnection(BankConnectionId("conn-1"), "PKO", "PL", Some("s"), ConnectionStatus.Active, None, None, importedAt))
      _   <- connRepo.createLink(ownLink)
      _   <- repo.insertNew(tx("t-int", "uid-1", "r-int", counterpartyAccount = Some("pl1020400000")))   // own, differently formatted
      _   <- repo.insertNew(tx("t-ext", "uid-1", "r-ext", counterpartyAccount = Some("DE00 1111 2222"))) // third party
      _   <- repo.insertNew(tx("t-none", "uid-1", "r-none", counterpartyAccount = None))                 // card/ATM
      _   <- repo.markInternalTransfers()
      all <- repo.list(None, None, None)
    } yield {
      all.find(_.id.value == "t-int").map(_.internal) shouldBe Some(true)
      all.find(_.id.value == "t-ext").map(_.internal) shouldBe Some(false)
      all.find(_.id.value == "t-none").map(_.internal) shouldBe Some(false)
    }
  }

  "markInternalTransfers flags own-movement descriptors that carry no counterparty IBAN (card repayment, auto-save)" in {
    val repo = new BankTransactionRepositoryImpl(xa)
    for {
      _   <- repo.insertNew(
               tx(
                 "t-card",
                 "uid-1",
                 "r-card",
                 amountCents = 150000,
                 counterpartyAccount = None,
                 remittance = Some("SPŁATA NALEŻNOŚCI - DZIĘKUJEMY REPAYMENT"),
               ),
             )
      _   <- repo.insertNew(
               tx("t-save", "uid-1", "r-save", amountCents = 50000, counterpartyAccount = None, remittance = Some("AUTOOSZCZĘDZANIE-PRZELEW AUTOSAVER")),
             )
      _   <- repo.insertNew(tx("t-shop", "uid-1", "r-shop", counterpartyAccount = None, remittance = Some("PayPro SA ALLEGRO")))
      _   <- repo.markInternalTransfers()
      all <- repo.list(None, None, None)
    } yield {
      all.find(_.id.value == "t-card").map(_.internal) shouldBe Some(true)
      all.find(_.id.value == "t-save").map(_.internal) shouldBe Some(true)
      all.find(_.id.value == "t-shop").map(_.internal) shouldBe Some(false)
    }
  }

  "query filters by category and hides internal transfers" in {
    val repo     = new BankTransactionRepositoryImpl(xa)
    val catRepo  = new CategoryRepositoryImpl(xa)
    val connRepo = new BankConnectionRepositoryImpl(xa)
    val ownLink  = BankAccountLink(
      BankAccountLinkId("l-own"),
      BankConnectionId("conn-1"),
      "uid-own",
      BankLinkTarget.Unlinked,
      iban = Some("PL99"),
      name = None,
      currency = None,
      product = None,
      lastBalanceCents = None,
      lastBalanceCurrency = None,
      lastSyncedAt = None,
    )
    for {
      _                      <- catRepo.create(Category(CategoryId("cat-1"), "Groceries", None))
      _                      <- connRepo.create(BankConnection(BankConnectionId("conn-1"), "PKO", "PL", Some("s"), ConnectionStatus.Active, None, None, importedAt))
      _                      <- connRepo.createLink(ownLink)
      _                      <- repo.insertNew(tx("t-uncat", "uid-1", "r1"))
      _                      <- repo.insertNew(tx("t-cat", "uid-1", "r2", categoryId = Some(CategoryId("cat-1"))))
      _                      <- repo.insertNew(tx("t-int", "uid-1", "r3", counterpartyAccount = Some("PL99")))
      _                      <- repo.markInternalTransfers()
      (uncat, uncatTotal, _) <- repo.query(None, None, None, None, Some("uncategorized"), hideInternal = false, "date", asc = false, None)
      (cat, _, _)            <- repo.query(None, None, None, None, Some("cat-1"), hideInternal = false, "date", asc = false, None)
      (visible, _, _)        <- repo.query(None, None, None, None, Some("all"), hideInternal = true, "date", asc = false, None)
    } yield {
      uncat.map(_.id.value) shouldBe List("t-uncat")                 // categorized + internal both excluded from the triage view
      uncatTotal shouldBe 1
      cat.map(_.id.value) shouldBe List("t-cat")
      visible.map(_.id.value).toSet shouldBe Set("t-uncat", "t-cat") // hideInternal drops t-int
    }
  }

  "query sorts, applies the month filter, and caps rows while reporting the true total" in {
    val repo = new BankTransactionRepositoryImpl(xa)
    val jan1 = Instant.parse("2026-01-05T00:00:00Z")
    val jan2 = Instant.parse("2026-01-20T00:00:00Z")
    val feb  = Instant.parse("2026-02-10T00:00:00Z")
    for {
      _                      <- repo.insertNew(tx("t-a", "uid-1", "ra", amountCents = -500, bookedAt = jan1))
      _                      <- repo.insertNew(tx("t-b", "uid-1", "rb", amountCents = -9000, bookedAt = jan2))
      _                      <- repo.insertNew(tx("t-c", "uid-1", "rc", amountCents = -1000, bookedAt = feb))
      (janRows, janTotal, _) <- repo.query(None, Some("2026-01"), None, None, Some("all"), hideInternal = false, "date", asc = false, None)
      (capped, total, sums)  <- repo.query(None, None, None, None, Some("all"), hideInternal = false, "amount", asc = true, Some(2))
      (windowRows, _, _)     <- repo.query(None, None, Some(jan2), Some(feb), Some("all"), hideInternal = false, "date", asc = false, None)
    } yield {
      janRows.map(_.id.value) shouldBe List("t-b", "t-a") // date desc within January
      janTotal shouldBe 2
      capped.map(_.id.value) shouldBe List("t-b", "t-c")  // amount asc (most negative first), capped to 2
      total shouldBe 3                                    // total reflects all matches, before the cap
      sums.map(_._2).sum shouldBe -10500                  // net sum is over ALL matches, not just the 2 returned rows
      windowRows.map(_.id.value) shouldBe List("t-b")     // [jan2, feb): includes jan2, excludes feb
    }
  }

  "distinctMonths returns the YYYY-MM buckets present, newest first" in {
    val repo = new BankTransactionRepositoryImpl(xa)
    for {
      _      <- repo.insertNew(tx("t-1", "uid-1", "r1", bookedAt = Instant.parse("2026-01-05T00:00:00Z")))
      _      <- repo.insertNew(tx("t-2", "uid-1", "r2", bookedAt = Instant.parse("2026-03-05T00:00:00Z")))
      _      <- repo.insertNew(tx("t-3", "uid-2", "r3", bookedAt = Instant.parse("2026-01-25T00:00:00Z")))
      months <- repo.distinctMonths()
    } yield months shouldBe List("2026-03", "2026-01")
  }

  "spendByCategoryBetween sums categorized outflows per category within the window, excluding inflows/uncategorized" in {
    val repo    = new BankTransactionRepositoryImpl(xa)
    val catRepo = new CategoryRepositoryImpl(xa)
    val jan     = Instant.parse("2026-01-10T00:00:00Z")
    val feb     = Instant.parse("2026-02-10T00:00:00Z")
    for {
      _       <- catRepo.create(Category(CategoryId("cat-1"), "Groceries", None))
      _       <- repo.insertNew(tx("t1", "uid-1", "r1", amountCents = -1000, bookedAt = jan, categoryId = Some(CategoryId("cat-1"))))
      _       <- repo.insertNew(tx("t2", "uid-1", "r2", amountCents = -2000, bookedAt = feb, categoryId = Some(CategoryId("cat-1"))))
      _       <- repo.insertNew(tx("t3", "uid-1", "r3", amountCents = 5000, bookedAt = feb, categoryId = Some(CategoryId("cat-1")))) // inflow → excluded
      _       <- repo.insertNew(tx("t4", "uid-1", "r4", amountCents = -3000, bookedAt = feb, categoryId = None))                     // uncategorized → excluded
      all     <- repo.spendByCategoryBetween(Instant.parse("2026-01-01T00:00:00Z"), Some(Instant.parse("2026-03-01T00:00:00Z")))
      janOnly <- repo.spendByCategoryBetween(Instant.parse("2026-01-01T00:00:00Z"), Some(Instant.parse("2026-02-01T00:00:00Z")))
    } yield {
      all shouldBe List((CategoryId("cat-1"), Currency.PLN, 3000L))     // 1000 + 2000, inflow and uncategorized dropped
      janOnly shouldBe List((CategoryId("cat-1"), Currency.PLN, 1000L)) // upper bound is exclusive
    }
  }

  "monthlySpendByCategory breaks spend down per YYYY-MM (so a doubled month can be medianed away)" in {
    val repo    = new BankTransactionRepositoryImpl(xa)
    val catRepo = new CategoryRepositoryImpl(xa)
    for {
      _    <- catRepo.create(Category(CategoryId("cat-1"), "Szkoła", None))
      // April: one payment; May: TWO payments (a slipped bill landed with the regular one); June: one payment.
      _    <- repo.insertNew(
                tx("a", "uid-1", "ra", amountCents = -1500, bookedAt = Instant.parse("2026-04-10T00:00:00Z"), categoryId = Some(CategoryId("cat-1"))),
              )
      _    <- repo.insertNew(
                tx("b1", "uid-1", "rb1", amountCents = -1500, bookedAt = Instant.parse("2026-05-02T00:00:00Z"), categoryId = Some(CategoryId("cat-1"))),
              )
      _    <- repo.insertNew(
                tx("b2", "uid-1", "rb2", amountCents = -1500, bookedAt = Instant.parse("2026-05-28T00:00:00Z"), categoryId = Some(CategoryId("cat-1"))),
              )
      _    <- repo.insertNew(
                tx("c", "uid-1", "rc", amountCents = -1500, bookedAt = Instant.parse("2026-06-10T00:00:00Z"), categoryId = Some(CategoryId("cat-1"))),
              )
      rows <- repo.monthlySpendByCategory(Instant.parse("2026-04-01T00:00:00Z"), Instant.parse("2026-07-01T00:00:00Z"))
    } yield {
      rows.map(r => (r._3, r._4)).sortBy(_._1) shouldBe List(("2026-04", 1500L), ("2026-05", 3000L), ("2026-06", 1500L))
      // The mean would be (1500+3000+1500)/3 = 2000; the median of the three months is 1500 — the true monthly figure.
    }
  }

  "deleteByConnection removes only that connection's transactions" in {
    val repo = new BankTransactionRepositoryImpl(xa)
    for {
      _   <- repo.insertNew(tx("t-1", "uid-1", "r-1", connId = "conn-1"))
      _   <- repo.insertNew(tx("t-2", "uid-2", "r-2", connId = "conn-2"))
      _   <- repo.deleteByConnection(BankConnectionId("conn-1"))
      all <- repo.list(None, None, None)
    } yield all.map(_.id.value) shouldBe List("t-2")
  }
}
