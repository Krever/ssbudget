-- A category can stand for transfers to and from one investment account. That account has no bank ledger in the app, so its
-- deposits and withdrawals are seen only on the other side, as transactions of this category. NULL = an ordinary category.
ALTER TABLE categories ADD COLUMN transfer_account_id TEXT REFERENCES accounts(id);
