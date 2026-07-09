-- Unify bank accounts and savings accounts into a single `accounts` table.
-- The current balance and its provenance now live on the account; balance_snapshots become history only.

ALTER TABLE accounts ADD COLUMN role TEXT NOT NULL DEFAULT 'spending';       -- 'spending' | 'savings'
ALTER TABLE accounts ADD COLUMN balance_cents INTEGER NOT NULL DEFAULT 0;    -- current balance
ALTER TABLE accounts ADD COLUMN savings_target INTEGER;                      -- monthly target (savings only)
ALTER TABLE accounts ADD COLUMN balance_source TEXT NOT NULL DEFAULT 'manual'; -- 'manual' | 'bank' | 'card_group'
ALTER TABLE accounts ADD COLUMN balance_updated_at TEXT;                     -- ISO-8601, when balance last set

-- Backfill existing spending accounts' current balance from their latest snapshot.
UPDATE accounts SET
    balance_cents = COALESCE(
        (SELECT b.amount FROM balance_snapshots b WHERE b.account_id = accounts.id ORDER BY b.recorded_at DESC LIMIT 1),
        0
    ),
    balance_updated_at =
        (SELECT b.recorded_at FROM balance_snapshots b WHERE b.account_id = accounts.id ORDER BY b.recorded_at DESC LIMIT 1);

-- Migrate savings accounts into the unified table (ids are stable UUIDs; savings_transactions.account_id keeps pointing at them).
INSERT INTO accounts (id, name, currency, role, balance_cents, savings_target, balance_source, balance_updated_at)
SELECT id, name, currency, 'savings', current_balance, planned_monthly, 'manual', NULL
FROM savings_accounts;

DROP TABLE savings_accounts;
