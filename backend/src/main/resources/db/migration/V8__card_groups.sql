-- Shared-limit credit-card groups. The remaining limit is mirrored onto a user-chosen app account
-- (account_id, nullable until the user links one; that account's balance_source becomes 'card_group').

CREATE TABLE card_groups (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    limit_cents INTEGER NOT NULL,
    currency TEXT NOT NULL,
    account_id TEXT REFERENCES accounts(id)
);
