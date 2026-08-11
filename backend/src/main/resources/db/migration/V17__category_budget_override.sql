-- Per-period manual override of a category budget's remaining (still-to-spend) amount. Set it when the computed reserve is wrong for this period,
-- typically because the bill was already paid without a matching transaction landing in a tracked account. 0 means "nothing left to pay", so an
-- overridden-to-zero budget counts as paid. Keyed by period so an override never leaks into the next one; deleting the row restores the computed value.
CREATE TABLE category_budget_overrides (
    period_id TEXT NOT NULL REFERENCES periods(id),
    category_id TEXT NOT NULL REFERENCES categories(id),
    remaining_cents INTEGER NOT NULL, -- in the primary currency, >= 0
    updated_at TEXT NOT NULL,         -- ISO 8601 timestamp
    PRIMARY KEY (period_id, category_id)
);
