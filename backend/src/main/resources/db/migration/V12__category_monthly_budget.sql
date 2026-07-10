-- Categories can be flagged as a monthly budget: when set, the category's rolling 3-month average spend becomes a budget
-- shown on the budget page (progress bar + pace) and folded into predicted expenses.
ALTER TABLE categories ADD COLUMN monthly_budget INTEGER NOT NULL DEFAULT 0;
