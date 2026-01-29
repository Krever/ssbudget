-- Currency settings migration
-- Move from hardcoded PLN/EUR enum to configurable currency settings

-- Create currency_settings table
CREATE TABLE currency_settings (
    code TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    is_primary INTEGER NOT NULL DEFAULT 0,
    enabled_at TEXT NOT NULL
);

-- Ensure only one primary currency (SQLite partial unique index)
CREATE UNIQUE INDEX idx_currency_primary ON currency_settings(is_primary) WHERE is_primary = 1;

-- Seed initial data with PLN as primary (using ISO 8601 format for Java Instant parsing)
INSERT INTO currency_settings (code, name, is_primary, enabled_at) VALUES ('PLN', 'Polish Zloty', 1, strftime('%Y-%m-%dT%H:%M:%SZ', 'now'));
INSERT INTO currency_settings (code, name, is_primary, enabled_at) VALUES ('EUR', 'Euro', 0, strftime('%Y-%m-%dT%H:%M:%SZ', 'now'));

-- SQLite does not support ALTER TABLE DROP CONSTRAINT, so we need to recreate tables
-- to remove the CHECK constraints. For existing data, we'll preserve it.

-- Recreate accounts table without CHECK constraint
CREATE TABLE accounts_new (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    currency TEXT NOT NULL REFERENCES currency_settings(code)
);
INSERT INTO accounts_new SELECT id, name, currency FROM accounts;
DROP TABLE accounts;
ALTER TABLE accounts_new RENAME TO accounts;

-- Recreate balance_snapshots table without CHECK constraint
CREATE TABLE balance_snapshots_new (
    id TEXT PRIMARY KEY,
    account_id TEXT NOT NULL REFERENCES accounts(id),
    amount INTEGER NOT NULL,
    currency TEXT NOT NULL REFERENCES currency_settings(code),
    recorded_at TEXT NOT NULL
);
INSERT INTO balance_snapshots_new SELECT id, account_id, amount, currency, recorded_at FROM balance_snapshots;
DROP TABLE balance_snapshots;
ALTER TABLE balance_snapshots_new RENAME TO balance_snapshots;

-- Recreate exchange_rates table without CHECK constraint
CREATE TABLE exchange_rates_new (
    from_currency TEXT NOT NULL REFERENCES currency_settings(code),
    to_currency TEXT NOT NULL REFERENCES currency_settings(code),
    rate INTEGER NOT NULL,
    fetched_at TEXT NOT NULL
);
INSERT INTO exchange_rates_new SELECT from_currency, to_currency, rate, fetched_at FROM exchange_rates;
DROP TABLE exchange_rates;
ALTER TABLE exchange_rates_new RENAME TO exchange_rates;

-- Recreate savings_accounts table without CHECK constraint
CREATE TABLE savings_accounts_new (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    currency TEXT NOT NULL REFERENCES currency_settings(code),
    current_balance INTEGER NOT NULL DEFAULT 0,
    planned_monthly INTEGER
);
INSERT INTO savings_accounts_new SELECT id, name, currency, current_balance, planned_monthly FROM savings_accounts;
DROP TABLE savings_accounts;
ALTER TABLE savings_accounts_new RENAME TO savings_accounts;

-- Recreate indexes that were dropped
CREATE INDEX idx_balance_snapshots_account ON balance_snapshots(account_id);
CREATE INDEX idx_balance_snapshots_recorded ON balance_snapshots(recorded_at);
CREATE INDEX idx_exchange_rates_currencies ON exchange_rates(from_currency, to_currency);
CREATE INDEX idx_exchange_rates_fetched ON exchange_rates(fetched_at);
