-- Planned saving: an amount per period that is to be moved to the savings accounts.
--
-- Until it is moved, the money is in the spending balance, and free money would count it as free. The part that is not yet moved is thus
-- subtracted from free money. When more than the plan is moved, the excess is added back, up to what is still to pay (see the second column). How much was moved is not typed by hand: it is read from the bank ledger (see BudgetSnapshotService).
--
-- The amount is on the period, in the primary currency. A new period starts with the amount of the period before it.
ALTER TABLE periods ADD COLUMN planned_saving_cents INTEGER NOT NULL DEFAULT 0;

-- What the planned saving takes from free money on the day; one more term of free money. Positive: the part of the plan that was not yet moved.
-- Negative: more than the plan was moved, and the excess is counted as money for the bills still to pay. Zero for all rows from before this column.
ALTER TABLE daily_budget_snapshots ADD COLUMN saving_reserve_cents INTEGER NOT NULL DEFAULT 0;
