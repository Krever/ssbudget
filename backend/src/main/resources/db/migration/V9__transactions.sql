-- Spending categories (a transaction may be classified into one) and imported bank transactions.

CREATE TABLE categories (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    color TEXT
);

CREATE TABLE bank_transactions (
    id TEXT PRIMARY KEY,
    connection_id TEXT NOT NULL REFERENCES bank_connections(id),
    eb_account_uid TEXT NOT NULL,
    entry_reference TEXT,               -- the bank's own transaction id, when present
    dedup_key TEXT NOT NULL,            -- entry_reference, else a stable hash of salient fields
    amount_cents INTEGER NOT NULL,      -- signed: negative = debit/outflow, positive = credit/inflow
    currency TEXT NOT NULL,
    status TEXT NOT NULL,               -- 'booked' | 'pending'
    booked_at TEXT NOT NULL,            -- ISO-8601; drives period assignment + display
    counterparty_name TEXT,
    counterparty_account TEXT,
    remittance TEXT,
    bank_transaction_code TEXT,
    category_id TEXT REFERENCES categories(id),
    raw_json TEXT NOT NULL,             -- full Enable Banking payload, always retained
    imported_at TEXT NOT NULL
);

-- Dedup: a given bank account never stores the same transaction twice (import is idempotent / backfill-safe).
CREATE UNIQUE INDEX ux_bank_tx_dedup ON bank_transactions(eb_account_uid, dedup_key);
CREATE INDEX idx_bank_tx_booked ON bank_transactions(booked_at);
CREATE INDEX idx_bank_tx_category ON bank_transactions(category_id);
CREATE INDEX idx_bank_tx_account ON bank_transactions(eb_account_uid);
