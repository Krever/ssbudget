-- Free-text user comment on a transaction. Nullable; preserved across re-imports (never overwritten by the upsert).
ALTER TABLE bank_transactions ADD COLUMN note TEXT;
