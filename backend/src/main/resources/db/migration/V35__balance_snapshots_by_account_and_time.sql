-- "The last recorded balance of an account before a moment" is asked on each pass of the daily record. With an index on the account alone,
-- the database read every record of the account to find it. Balances are recorded at each sync (about 20 a day in all), so that read grew
-- without end.
CREATE INDEX idx_balance_snapshots_account_time ON balance_snapshots(account_id, recorded_at);
