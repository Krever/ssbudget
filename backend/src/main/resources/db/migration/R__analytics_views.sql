-- Reporting views for Metabase — a REPEATABLE migration.
--
-- These are derived artifacts, not state: they must always match the current schema. Flyway re-runs a
-- repeatable migration whenever its checksum changes, so editing this file is enough to update them —
-- unlike a versioned migration, which would leave the views stale until someone remembered to write
-- the next one. (V20 created them originally and also created analytics_state, which IS state and so
-- stays versioned.)
--
-- The app schema is optimised for the app: money is integer cents, timestamps are ISO strings, and
-- everything interesting needs a join. These views are the semantic layer Metabase explores instead:
-- major-unit amounts, resolved names, ISO dates.
--
-- Two things are deliberate:
--   * amounts are multiplied by 1.0 so Metabase's value-based type inference sees a float even when
--     a column happens to be all zeros in the sample it reads;
--   * dates stay ISO-8601 text (SQLite has no date type). Metabase syncs them as text and the
--     provisioner applies a `Coercion/ISO8601->Date` to each, which is what makes time-series
--     grouping and date filters work.

DROP VIEW IF EXISTS v_transactions;
CREATE VIEW v_transactions AS
SELECT
  t.id                                    AS id,
  date(t.booked_at)                       AS booked_on,
  strftime('%Y-%m', t.booked_at)          AS month,
  t.amount_cents * 1.0 / 100              AS amount,
  CASE WHEN t.amount_cents < 0 THEN -t.amount_cents * 1.0 / 100 ELSE 0.0 END AS outflow,
  CASE WHEN t.amount_cents > 0 THEN  t.amount_cents * 1.0 / 100 ELSE 0.0 END AS inflow,
  t.currency                              AS currency,
  COALESCE(c.name, 'Uncategorized')       AS category,
  t.counterparty_name                     AS counterparty,
  t.remittance                            AS description,
  CASE WHEN t.is_internal = 1 THEN 'yes' ELSE 'no' END AS internal_transfer,
  COALESCE(t.category_source, 'none')     AS category_source,
  t.status                                AS status,
  t.note                                  AS note
FROM bank_transactions t
LEFT JOIN categories c ON c.id = t.category_id;

DROP VIEW IF EXISTS v_monthly_category_spend;
CREATE VIEW v_monthly_category_spend AS
SELECT
  month,
  category,
  ROUND(SUM(outflow), 2) AS spend,
  COUNT(*)               AS transactions
FROM v_transactions
WHERE internal_transfer = 'no' AND outflow > 0
GROUP BY month, category;

DROP VIEW IF EXISTS v_accounts;
CREATE VIEW v_accounts AS
SELECT
  a.name                    AS name,
  a.role                    AS role,
  a.currency                AS currency,
  a.balance_cents * 1.0 / 100 AS balance,
  a.balance_source          AS balance_source,
  a.balance_updated_at      AS balance_updated_at
FROM accounts a;

DROP VIEW IF EXISTS v_planned_items;
CREATE VIEW v_planned_items AS
SELECT
  d.name                          AS name,
  d.item_type                     AS item_type,
  d.currency                      AS currency,
  d.estimate_cents * 1.0 / 100    AS estimate,
  COALESCE(r.paid_amount, 0) * 1.0 / 100 AS paid,
  CASE WHEN r.settled = 1 THEN 'yes' ELSE 'no' END AS settled,
  date(p.started_at)              AS period_start,
  date(r.paid_at)                 AS paid_on
FROM expense_definitions d
LEFT JOIN expense_records r ON r.expense_def_id = d.id
LEFT JOIN periods p ON p.id = r.period_id;
