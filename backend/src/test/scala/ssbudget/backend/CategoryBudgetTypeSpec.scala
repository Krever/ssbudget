package ssbudget.backend

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import ssbudget.shared.api.CategorySummary
import ssbudget.shared.model.*

/** The rule that decides how much of a category budget still has to move before the next paycheck.
  *
  * It comes in two halves, tested here in that order: [[CategoryBudgetType.remaining]] does the arithmetic on magnitudes and knows nothing about
  * direction, and [[CategorySummary]] owns the one rule for which way a category's money flows — which matters because spend is NET, so a category
  * whose money comes IN (rent from a tenant, a recurring refund) has a negative budget and must predict arrivals rather than spend.
  */
class CategoryBudgetTypeSpec extends AnyFreeSpec with Matchers {

  private val halfway = 0.5

  private def remaining(t: CategoryBudgetType, budget: Long, moved: Long, elapsed: Double = halfway): Long =
    CategoryBudgetType.remaining(t, budget, moved, elapsed)

  "The budget formulas, in magnitudes" - {
    "reserve the remaining-time share of a Steady budget, whatever has already moved" in {
      remaining(CategoryBudgetType.Steady, 100000L, 0L) shouldBe 50000L
      remaining(CategoryBudgetType.Steady, 100000L, 90000L) shouldBe 50000L // you still have to eat
      remaining(CategoryBudgetType.Steady, 100000L, 0L, 1.0) shouldBe 0L    // period over
    }

    "reserve a Bill in full until anything moves" in {
      remaining(CategoryBudgetType.Bill, 100000L, 0L) shouldBe 100000L
      remaining(CategoryBudgetType.Bill, 100000L, 1L) shouldBe 0L
      // Movement the wrong way is no progress: nothing has been paid (or received) yet.
      remaining(CategoryBudgetType.Bill, 100000L, -500L) shouldBe 100000L
    }

    "draw a Subscription pool down to zero and never below" in {
      remaining(CategoryBudgetType.Subscription, 100000L, 30000L) shouldBe 70000L
      remaining(CategoryBudgetType.Subscription, 100000L, 150000L) shouldBe 0L
    }
  }

  "A category summary" - {
    def summary(budget: Long, spent: Long, override_ : Option[Long] = None): CategorySummary =
      CategorySummary(
        Category(CategoryId("c"), "Tenant rent", None, Some(CategoryBudgetType.Bill)),
        avgMonthlyCents = budget,
        currentPeriodSpentCents = spent,
        lastPeriodSpentCents = 0L,
        currency = Currency.PLN,
        overrideRemainingCents = override_,
      )

    "recognizes which way the money flows" in {
      summary(-100000L, 0L).isIncome shouldBe true
      summary(100000L, 0L).isIncome shouldBe false
      // No history yet: the direction is inferred from what has actually moved this period.
      summary(0L, -5000L).isIncome shouldBe true
      summary(0L, 0L).isIncome shouldBe false
    }

    "reports its figures as magnitudes in its own direction" in {
      val expense = summary(100000L, 30000L)
      expense.expectedMagnitude shouldBe 100000L
      expense.movedMagnitude shouldBe 30000L

      // Mirrored: the income's budget and its receipts are both negative on the wire, and both read positive here.
      val income = summary(-100000L, -30000L)
      income.expectedMagnitude shouldBe 100000L
      income.movedMagnitude shouldBe 30000L
    }

    "mirrors an expense budget for an income one" in {
      // A Bill: reserved in full until anything moves, in whichever direction it moves.
      summary(100000L, 0L).remainingMagnitude(halfway) shouldBe 100000L
      summary(-100000L, 0L).remainingMagnitude(halfway) shouldBe 100000L
      summary(100000L, 30000L).remainingMagnitude(halfway) shouldBe 0L
      summary(-100000L, -30000L).remainingMagnitude(halfway) shouldBe 0L
    }

    "signs what's remaining so the totals can add it up" in {
      // Positive is still to be spent, negative is still expected to arrive — this is the form the free-money sums consume.
      summary(100000L, 0L).remainingCents(halfway) shouldBe 100000L
      summary(-100000L, 0L).remainingCents(halfway) shouldBe -100000L
    }

    "takes a manual override as given, sign included" in {
      summary(100000L, 0L, override_ = Some(25000L)).remainingCents(halfway) shouldBe 25000L
      summary(-100000L, 0L, override_ = Some(-25000L)).remainingCents(halfway) shouldBe -25000L
      // An override is a magnitude to whoever renders it, whichever way the category runs.
      summary(-100000L, 0L, override_ = Some(-25000L)).remainingMagnitude(halfway) shouldBe 25000L
      // Zero means covered in either direction — that's what the "Paid"/"Received" shortcut writes.
      summary(-100000L, 0L, override_ = Some(0L)).remainingCents(halfway) shouldBe 0L
    }
  }
}
