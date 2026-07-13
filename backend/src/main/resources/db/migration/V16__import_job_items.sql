-- Per-account breakdown of an import/sync run (the drill-down level): JSON array of {connection, account, status, imported, skipped, error}.
ALTER TABLE import_jobs ADD COLUMN items TEXT NOT NULL DEFAULT '[]';
