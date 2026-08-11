-- Drop `estimate_mode` from budget items, and make the estimate a plain NOT NULL amount.
--
-- `estimate_mode` (fixed | last_month | average) came straight from the original spec — a planned item's estimate was going to be derivable from
-- history instead of typed in. It was never implemented: no code ever branched on it, no UI ever set it, and the create path always wrote 'fixed'.
-- Deriving expectations from history is now what Category Budgets do, from real transactions, so the flag has nothing left to mean.
--
-- Consequently `fixed_estimate` was only nullable to cover the non-fixed modes ("only for fixed mode"), so it becomes NOT NULL and loses the now
-- meaningless `fixed_` prefix: estimate_cents.
--
-- This needs a table rebuild rather than ALTER TABLE ... DROP COLUMN: SQLite refuses to drop a column named in a CHECK constraint, and
-- estimate_mode has one. Same create-copy-drop-rename dance as V3 (foreign keys are not enabled, so expense_records' reference rides along).
-- Rebuilding also lets the item_type CHECK drop 'estimated_expense', which V18 removed from the code but left permitted here.

CREATE TABLE expense_definitions_new (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    item_type TEXT NOT NULL CHECK (item_type IN ('planned_expense', 'planned_income')),
    estimate_cents INTEGER NOT NULL, -- in cents; 0 for the (never user-reachable) rows that had no estimate
    currency TEXT NOT NULL REFERENCES currency_settings(code)
);

INSERT INTO expense_definitions_new (id, name, item_type, estimate_cents, currency)
SELECT id, name, item_type, COALESCE(fixed_estimate, 0), currency
FROM expense_definitions;

DROP TABLE expense_definitions;

ALTER TABLE expense_definitions_new RENAME TO expense_definitions;
