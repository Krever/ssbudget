-- The application now turns foreign keys ON (`Database.hikariConfig`). Until now SQLite did not check them, so a REFERENCES in this schema was
-- a comment. Two of them state a rule that the application does not have. They go, so that each one that stays is true and is enforced.
--
--   1. exchange_rates.from_currency / to_currency -> currency_settings. A rate refresh stores the rate of each currency that the source knows
--      (about 30), not only of the enabled ones, so that an amount in any currency can be converted.
--   2. bank_transactions.connection_id -> bank_connections. A transaction is kept after its bank connection is removed (a disconnect, or an
--      account that a renewed consent does not have any more): the ledger is history. The column stays, as a record of where the row came from.
--
-- SQLite cannot drop a constraint, so each table is made again with the same columns, in the same order, and the rows are copied. No row and no
-- value changes.
--
-- The reporting views read these tables, and SQLite refuses a rename while a view names a table that does not exist. They are derived, not
-- state: they go here, and R__analytics_views makes them again after this migration (its text changed with this one, so Flyway runs it).
DROP VIEW IF EXISTS v_daily_budget;
DROP VIEW IF EXISTS v_accounts;
DROP VIEW IF EXISTS v_monthly_category_spend;
DROP VIEW IF EXISTS v_transactions;

CREATE TABLE exchange_rates_new (
    from_currency TEXT NOT NULL,
    to_currency TEXT NOT NULL,
    rate INTEGER NOT NULL,
    fetched_at TEXT NOT NULL
);
INSERT INTO exchange_rates_new (from_currency, to_currency, rate, fetched_at)
SELECT from_currency, to_currency, rate, fetched_at FROM exchange_rates;
DROP TABLE exchange_rates;
ALTER TABLE exchange_rates_new RENAME TO exchange_rates;
CREATE INDEX idx_exchange_rates_currencies ON exchange_rates(from_currency, to_currency);
CREATE INDEX idx_exchange_rates_fetched ON exchange_rates(fetched_at);

CREATE TABLE bank_transactions_new (
    id TEXT PRIMARY KEY,
    connection_id TEXT NOT NULL,        -- the connection that imported the row; that connection can be gone
    eb_account_uid TEXT NOT NULL,
    entry_reference TEXT,               -- the bank's own transaction id, when present
    dedup_key TEXT NOT NULL,            -- entry_reference, else a stable hash of salient fields
    amount_cents INTEGER NOT NULL,      -- signed: negative = debit/outflow, positive = credit/inflow
    currency TEXT NOT NULL,
    status TEXT NOT NULL,               -- 'booked' | 'pending'
    booked_at TEXT NOT NULL,            -- an instant; drives period assignment + display
    counterparty_name TEXT,
    counterparty_account TEXT,
    remittance TEXT,
    bank_transaction_code TEXT,
    category_id TEXT REFERENCES categories(id),
    raw_json TEXT NOT NULL,             -- full Enable Banking payload, always retained
    imported_at TEXT NOT NULL,
    is_internal INTEGER NOT NULL DEFAULT 0,
    category_source TEXT,
    note TEXT
);
INSERT INTO bank_transactions_new
    (id, connection_id, eb_account_uid, entry_reference, dedup_key, amount_cents, currency, status, booked_at, counterparty_name,
     counterparty_account, remittance, bank_transaction_code, category_id, raw_json, imported_at, is_internal, category_source, note)
SELECT id, connection_id, eb_account_uid, entry_reference, dedup_key, amount_cents, currency, status, booked_at, counterparty_name,
       counterparty_account, remittance, bank_transaction_code, category_id, raw_json, imported_at, is_internal, category_source, note
FROM bank_transactions;
DROP TABLE bank_transactions;
ALTER TABLE bank_transactions_new RENAME TO bank_transactions;
CREATE UNIQUE INDEX ux_bank_tx_dedup ON bank_transactions(eb_account_uid, dedup_key);
CREATE INDEX idx_bank_tx_booked ON bank_transactions(booked_at);
CREATE INDEX idx_bank_tx_category ON bank_transactions(category_id);
CREATE INDEX idx_bank_tx_account ON bank_transactions(eb_account_uid);
CREATE INDEX idx_bank_tx_internal ON bank_transactions(is_internal);
