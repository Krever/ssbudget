-- Accounts now record an opening zero balance when they are created, so money put into a new savings account counts as added in the period it
-- was created. Backfill that zero for savings accounts created in the open period before this change: one second before the first recorded balance.
-- Accounts that already have a zero snapshot are skipped.
INSERT INTO balance_snapshots (id, account_id, amount, currency, recorded_at)
SELECT lower(hex(randomblob(4)) || '-' || hex(randomblob(2)) || '-' || hex(randomblob(2)) || '-' || hex(randomblob(2)) || '-' || hex(randomblob(6))),
       a.id,
       0,
       a.currency,
       strftime('%Y-%m-%dT%H:%M:%fZ', MIN(b.recorded_at), '-1 second')
FROM accounts a
JOIN balance_snapshots b ON b.account_id = a.id
WHERE a.role = 'savings'
GROUP BY a.id
HAVING MIN(b.recorded_at) > (SELECT started_at FROM periods WHERE ended_at IS NULL)
   AND MIN(CASE WHEN b.amount = 0 THEN 0 ELSE 1 END) = 1;
