-- Authentication schema

-- Auth config (singleton table for password)
CREATE TABLE auth_config (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    password_hash TEXT,           -- Argon2 hash
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

-- Sessions (HttpOnly cookie sessions)
CREATE TABLE sessions (
    token TEXT PRIMARY KEY,
    created_at TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    last_used_at TEXT NOT NULL
);

-- Passkey credentials (WebAuthn)
CREATE TABLE passkey_credentials (
    credential_id TEXT PRIMARY KEY,
    public_key_cose BLOB NOT NULL,
    sign_count INTEGER NOT NULL DEFAULT 0,
    display_name TEXT,
    created_at TEXT NOT NULL,
    last_used_at TEXT
);

CREATE INDEX idx_sessions_expires ON sessions(expires_at);
