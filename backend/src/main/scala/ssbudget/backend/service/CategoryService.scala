package ssbudget.backend.service

import cats.effect.IO
import cats.implicits.*
import ssbudget.backend.banking.RuleEngineService
import ssbudget.backend.db.Repositories
import ssbudget.shared.api.*
import ssbudget.shared.model.*

import java.util.UUID

/** The categories and their budgets: to make, change and delete a category, and to set by hand what a budget still expects in the open period.
  *
  * The rules of a category are here and not in the route file: a transfer category must name an investment account, and a category goes with all that
  * names it.
  */
class CategoryService(repos: Repositories, summaries: CategorySummaryService, ruleEngine: RuleEngineService, clock: AppClock = AppClock.system) {

  private type Result[A] = IO[Either[ApiError, A]]

  def create(dto: CreateCategory): Result[Category] = {
    val category = Category(CategoryId(UUID.randomUUID().toString), dto.name, dto.color, dto.budgetType)
    repos.categories.create(category).as(Right(category))
  }

  def update(id: CategoryId, dto: UpdateCategory): Result[Category] = {
    for {
      existingOpt <- repos.categories.findById(id)
      result      <- existingOpt match {
                       case Some(_) =>
                         val updated = dto.toCategory(id)
                         // A transfer category must name an investment account: only that role splits its change into moved and market.
                         updated.transferAccount.traverse(repos.accounts.findById).map(_.flatten).flatMap {
                           case Some(acc) if acc.role != AccountRole.Investment =>
                             IO.pure(Left(ApiError.invalid(s"${acc.name} is not an investment account")))
                           case None if updated.transferAccount.isDefined       =>
                             IO.pure(Left(ApiError.invalid("The account of this transfer category does not exist")))
                           case _                                               =>
                             repos.categories.update(updated).as(Right(updated))
                         }
                       case None    => IO.pure(Left(ApiError.notFound(s"Category not found: ${id.value}")))
                     }
    } yield result
  }

  /** Sets (`Some`) or clears (`None`) the manual remaining-amount override for a category budget in the CURRENT period, then returns the refreshed
    * summaries. The amount is stored with its sign: positive is still to be spent, negative is still expected to come in (an income category — see
    * [[CategoryBudgetType.remaining]]). 0 marks the budget as covered either way. `V17__category_budget_override.sql` predates income budgets and
    * comments the column as `>= 0`; the signed convention here supersedes it (the column itself has no constraint).
    */
  def setOverride(id: CategoryId, remainingCents: Option[Long]): Result[List[CategorySummary]] =
    for {
      catOpt    <- repos.categories.findById(id)
      periodOpt <- repos.periods.findCurrent
      result    <- (catOpt, periodOpt) match {
                     case (None, _)               => IO.pure(Left(ApiError.notFound(s"Category not found: ${id.value}")))
                     case (_, None)               => IO.pure(Left(ApiError.invalid("No active period")))
                     case (Some(_), Some(period)) =>
                       val write = remainingCents match {
                         case Some(cents) => clock.now.flatMap(repos.categoryBudgetOverrides.upsert(period.id, id, cents, _))
                         case None        => repos.categoryBudgetOverrides.delete(period.id, id)
                       }
                       write >> summaries.summaries.map(Right(_))
                   }
    } yield result

  def delete(id: CategoryId): Result[Unit] =
    // The category goes with all that names it, in one transaction; then the rules run again, because the rules for it are gone.
    (repos.categories.deleteWithDependents(id) *> ruleEngine.applyRules()).as(Right(()))
}
