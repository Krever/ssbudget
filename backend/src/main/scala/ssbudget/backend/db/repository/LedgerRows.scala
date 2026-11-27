package ssbudget.backend.db.repository

import ssbudget.shared.model.{AccountId, CategoryId, Currency, PeriodId}

import java.time.Instant

// The rows of the sums that the repositories give. Each was a tuple before (`(CategoryId, Currency, String, Long, Int, Int)`), and its reader
// took the fields by position: two fields of the same type in the wrong order compile, and give a wrong figure with no sign. A row with names
// cannot be read in the wrong order.

/** An amount of one category in one currency. What the amount is (gross spend, or net) is in the method that gives it. */
final case class CategoryAmount(category: CategoryId, currency: Currency, cents: Long)

/** An amount of one app account in one currency. */
final case class AccountAmount(account: AccountId, currency: Currency, cents: Long)

/** An amount of one bank account (by its uid at the bank) in one currency. */
final case class BankAccountAmount(accountUid: String, currency: Currency, cents: Long)

/** The spend of one category in one month. `month` is 'YYYY-MM'. */
final case class MonthlyCategoryAmount(category: CategoryId, currency: Currency, month: String, cents: Long)

/** The net spend of one category on one day (inflows subtract), and how many entries went out and came in. `day` is 'YYYY-MM-DD'. */
final case class DailyCategoryNet(category: CategoryId, currency: Currency, day: String, netCents: Long, outflows: Int, inflows: Int)

/** How many entries of one category went out and came in. */
final case class PaymentCounts(category: CategoryId, outflows: Int, inflows: Int)

/** The signed movement of one bank account on one day. `day` is 'YYYY-MM-DD'. */
final case class DailyAccountNet(accountUid: String, currency: Currency, day: String, cents: Long)

/** A manual override of what a category budget still expects in a period, with the time it was set. */
final case class StampedOverride(period: PeriodId, category: CategoryId, remainingCents: Long, updatedAt: Instant)
