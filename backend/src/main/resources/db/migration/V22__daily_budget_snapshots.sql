-- One row per day holding the TERMS of the free-money calculation, not its answer.
--
-- Free money is `spendable + still-to-receive − still-to-pay`. Storing that single number would make the chart cheap and the history useless: a dip
-- would never say whether the balance fell or a bill was merely reserved. The terms are stored; `v_daily_budget` does the arithmetic, so the formula
-- has one definition in SQL and the components stay the source of truth.
--
-- `days_remaining` IS stored rather than derived, because the rule for when a period ends (the next payday at least two weeks out, see
-- Period.expectedEnd) lives in Scala and has no SQL equivalent. Keeping it here also makes daily budget chartable without re-deriving anything.
--
-- `source` separates a row measured on the day from one inferred afterwards. The app suspends when idle (fly `min_machines_running = 0`), so days
-- nobody opened it are filled in later from bank transactions and payment dates — accurate in level, approximate in when a planned item stepped down.
-- A 'live' row is never replaced by a 'reconstructed' one.
CREATE TABLE daily_budget_snapshots (
    on_date                  TEXT PRIMARY KEY,   -- 'YYYY-MM-DD' (UTC)
    captured_at              TEXT NOT NULL,      -- ISO-8601 instant the row was written
    period_id                TEXT REFERENCES periods(id),
    currency                 TEXT NOT NULL,      -- primary currency at capture; every amount below is in it
    spendable_cents          INTEGER NOT NULL,   -- sum of spending accounts (savings excluded)
    planned_to_pay_cents     INTEGER NOT NULL,   -- sum of remaining, planned expenses
    planned_to_receive_cents INTEGER NOT NULL,   -- sum of remaining, planned incomes
    budgets_to_spend_cents   INTEGER NOT NULL,   -- sum of category-budget remaining that is still to be spent
    budgets_to_receive_cents INTEGER NOT NULL,   -- sum of category-budget remaining that is still to arrive, as a positive amount
    savings_cents            INTEGER NOT NULL,   -- sum of savings buckets; informational, never part of free money
    days_remaining           INTEGER,            -- to the period's expected end; NULL when no period was open
    source                   TEXT NOT NULL CHECK (source IN ('live', 'reconstructed'))
);

CREATE INDEX idx_daily_budget_snapshots_period ON daily_budget_snapshots(period_id);
