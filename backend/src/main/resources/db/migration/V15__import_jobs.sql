-- Tracked background import/sync runs, so the UI can show the current run, history, stats, and inspectable errors.
CREATE TABLE import_jobs (
    id TEXT PRIMARY KEY,
    kind TEXT NOT NULL,                     -- 'import' | 'sync_all'
    label TEXT NOT NULL,                    -- human label, e.g. "Sync all banks" or "Import — PKO"
    status TEXT NOT NULL,                   -- 'running' | 'succeeded' | 'failed'
    started_at TEXT NOT NULL,
    finished_at TEXT,
    imported INTEGER NOT NULL DEFAULT 0,
    skipped INTEGER NOT NULL DEFAULT 0,
    progress TEXT,                          -- current step while running
    errors TEXT NOT NULL DEFAULT '[]',      -- JSON array of per-connection warnings
    message TEXT                            -- fatal error message when failed
);

CREATE INDEX idx_import_jobs_started ON import_jobs(started_at);
