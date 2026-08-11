-- Trim the product surface: drop estimated expenses, one-time expenses and planned-savings targets; give planned items partial payments.
--
-- What replaces each:
--   * estimated expenses  -> category budgets (Steady/Bill/Subscription), which already derive variable spend from real transactions,
--   * one-time expenses   -> tags on bank transactions (not built yet; the feature is removed rather than kept half-used),
--   * planned savings     -> a planned expense named after the bucket; savings accounts keep only a balance.

-- 1. Estimated expenses. Their records go first (expense_records references expense_definitions).
DELETE FROM expense_records
WHERE expense_def_id IN (SELECT id FROM expense_definitions WHERE item_type = 'estimated_expense');

DELETE FROM expense_definitions WHERE item_type = 'estimated_expense';

-- 2. One-time expenses and the savings ledger (targets + transactions) are gone entirely.
DROP TABLE one_time_expenses;

DROP TABLE savings_transactions;

ALTER TABLE accounts DROP COLUMN savings_target;

-- 3. Planned items can now be paid in instalments: payments accumulate in paid_amount, and `settled` is what closes the item for the period.
--    Backfill marks every already-paid record as settled, preserving today's "paid = done" behaviour for existing data.
ALTER TABLE expense_records ADD COLUMN settled INTEGER NOT NULL DEFAULT 0;

UPDATE expense_records SET settled = 1 WHERE paid_amount IS NOT NULL;
