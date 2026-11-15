-- Access tokens for the MCP endpoint (/mcp). The token itself is shown one time, when it is made;
-- only its SHA-256 digest is stored, so a copy of this database does not give access.
CREATE TABLE mcp_tokens (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    token_hash TEXT NOT NULL UNIQUE,   -- hex SHA-256 of the token
    created_at TEXT NOT NULL,
    last_used_at TEXT
);
