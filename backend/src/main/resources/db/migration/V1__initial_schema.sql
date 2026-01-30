-- SSBudget Initial Schema
-- Money stored as INTEGER (cents), timestamps as TEXT (ISO 8601)

-- Accounts (bank accounts)
CREATE TABLE accounts (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    currency TEXT NOT NULL CHECK (currency IN ('PLN', 'EUR'))
);

-- Budget item definitions (planned expenses, estimated expenses, planned incomes)
CREATE TABLE expense_definitions (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    item_type TEXT NOT NULL CHECK (item_type IN ('planned_expense', 'estimated_expense', 'planned_income')),
    estimate_mode TEXT NOT NULL CHECK (estimate_mode IN ('fixed', 'last_month', 'average')),
    fixed_estimate INTEGER -- in cents, nullable (only for fixed mode)
);

-- Periods (budget periods, typically monthly)
CREATE TABLE periods (
    id TEXT PRIMARY KEY,
    started_at TEXT NOT NULL, -- ISO 8601 timestamp
    ended_at TEXT -- nullable until closed
);

-- Expense records (actual payments for planned expenses)
CREATE TABLE expense_records (
    id TEXT PRIMARY KEY,
    period_id TEXT NOT NULL REFERENCES periods(id),
    expense_def_id TEXT NOT NULL REFERENCES expense_definitions(id),
    paid_amount INTEGER, -- in cents, nullable until paid
    paid_at TEXT, -- ISO 8601 timestamp, nullable until paid
    UNIQUE (period_id, expense_def_id)
);

-- Balance snapshots (point-in-time account balances)
CREATE TABLE balance_snapshots (
    id TEXT PRIMARY KEY,
    account_id TEXT NOT NULL REFERENCES accounts(id),
    amount INTEGER NOT NULL, -- in cents
    currency TEXT NOT NULL CHECK (currency IN ('PLN', 'EUR')),
    recorded_at TEXT NOT NULL -- ISO 8601 timestamp
);

-- Exchange rates (no id, uses natural key)
CREATE TABLE exchange_rates (
    from_currency TEXT NOT NULL CHECK (from_currency IN ('PLN', 'EUR')),
    to_currency TEXT NOT NULL CHECK (to_currency IN ('PLN', 'EUR')),
    rate INTEGER NOT NULL, -- rate * 10000 for precision
    fetched_at TEXT NOT NULL -- ISO 8601 timestamp
);

-- Savings accounts (separate from bank accounts - editable balance, optional targets)
CREATE TABLE savings_accounts (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    currency TEXT NOT NULL CHECK (currency IN ('PLN', 'EUR')),
    current_balance INTEGER NOT NULL DEFAULT 0, -- in cents, editable directly
    planned_monthly INTEGER -- optional monthly target in cents
);

-- Savings transactions (inflows/outflows to savings accounts)
CREATE TABLE savings_transactions (
    id TEXT PRIMARY KEY,
    account_id TEXT NOT NULL REFERENCES savings_accounts(id),
    period_id TEXT NOT NULL REFERENCES periods(id),
    amount INTEGER NOT NULL, -- positive = inflow, negative = outflow
    note TEXT, -- optional context
    created_at TEXT NOT NULL -- ISO 8601 timestamp
);

-- Indexes for common queries
CREATE INDEX idx_expense_records_period ON expense_records(period_id);
CREATE INDEX idx_expense_records_def ON expense_records(expense_def_id);
CREATE INDEX idx_balance_snapshots_account ON balance_snapshots(account_id);
CREATE INDEX idx_balance_snapshots_recorded ON balance_snapshots(recorded_at);
CREATE INDEX idx_exchange_rates_currencies ON exchange_rates(from_currency, to_currency);
CREATE INDEX idx_exchange_rates_fetched ON exchange_rates(fetched_at);
CREATE INDEX idx_savings_transactions_account ON savings_transactions(account_id);
CREATE INDEX idx_savings_transactions_period ON savings_transactions(period_id);
