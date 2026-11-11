-- Three more terms for each day, all taken from the bank ledger and all cumulative since the period's first day.
--
-- Free money is a forecast of what is left at the period's end, so its line should only move when the plan was wrong. Two things move it that say
-- nothing about the plan: money taken from (or put into) savings, and income that no plan expected. With them stored, the line can be drawn
-- without them — and that line, unlike the raw one, has a slope that can be extended to the period's end.
--
--   ledger_cents            net of ALL movements on the spending accounts: what the ledger says the spendable balance did.
--   from_savings_cents      + money that came from outside the spending accounts (a transfer in from savings, or a budgeted bill paid directly
--                             from a savings account); negative when money went the other way (a transfer to savings).
--   unplanned_income_cents  net inflow of each category that is not a budget, counted only where the category brought in more than it took out.
--
-- `ledger_cents` is stored although `spendable_cents` is already there, because the two do not move on the same day: a balance is read when the
-- bank is synced, a transaction carries the bank's booking date, and the two are often a day apart. The other two terms are dated by the ledger,
-- so only the ledger's own figure can be set against them without a false step up on one day and down on the next.
--
-- All three count from the day AFTER the period opened: the first day's row is the baseline, and the paycheck that opened the period is not
-- "unplanned income". All are in the primary currency.
--
-- NULL means "not computed yet": rows written before this migration. BudgetSnapshotService fills them on its next pass, because the terms are a
-- function of the transactions and need no measurement from the day itself.
ALTER TABLE daily_budget_snapshots ADD COLUMN ledger_cents INTEGER;
ALTER TABLE daily_budget_snapshots ADD COLUMN from_savings_cents INTEGER;
ALTER TABLE daily_budget_snapshots ADD COLUMN unplanned_income_cents INTEGER;

-- Reconstructed rows are dropped, to be rebuilt on the next pass. They carried a defect: movements on an account in a foreign currency were
-- converted to the primary currency twice, so one foreign invoice could add several times its value to a day's balance. Measured rows are kept.
DELETE FROM daily_budget_snapshots WHERE source = 'reconstructed';
