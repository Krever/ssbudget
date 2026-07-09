-- Enable Banking: connections + account links.
-- A link caches the bank-reported metadata and last-seen balance, and points at a BankLinkTarget
-- (none | account | card_group) via (link_target_kind, link_target_id). It never holds an app
-- account's own balance — that lives on `accounts`.

CREATE TABLE bank_connections (
    id TEXT PRIMARY KEY,
    aspsp_name TEXT NOT NULL,
    aspsp_country TEXT NOT NULL,
    session_id TEXT,
    status TEXT NOT NULL,
    valid_until TEXT,
    auth_state TEXT,
    created_at TEXT NOT NULL
);

CREATE INDEX idx_bank_connections_auth_state ON bank_connections(auth_state);

CREATE TABLE bank_account_links (
    id TEXT PRIMARY KEY,
    connection_id TEXT NOT NULL REFERENCES bank_connections(id),
    eb_account_uid TEXT NOT NULL,
    link_target_kind TEXT NOT NULL DEFAULT 'none', -- 'none' | 'account' | 'card_group'
    link_target_id TEXT,                           -- account id or card group id, depending on kind
    iban TEXT,
    name TEXT,
    currency TEXT,
    product TEXT,
    last_balance_cents INTEGER,
    last_balance_currency TEXT,
    last_synced_at TEXT,
    UNIQUE(connection_id, eb_account_uid)
);

CREATE INDEX idx_bank_account_links_connection ON bank_account_links(connection_id);
CREATE INDEX idx_bank_account_links_target ON bank_account_links(link_target_kind, link_target_id);
