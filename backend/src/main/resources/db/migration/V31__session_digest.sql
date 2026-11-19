-- A session is now stored by the SHA-256 digest of its token, not by the token (as an MCP token is): a copy of the database (a backup, the
-- export) must not hold working logins. The column keeps its name; its content is the digest.
--
-- The rows of before hold tokens in plain text. They go: each device logs in one more time. No user data is lost.
DELETE FROM sessions;

-- When the owner of the session last proved their identity (a login, or a confirmation with the password or a passkey). An action with a
-- lasting effect (export, import, a passkey change) needs a recent proof, not only the session.
ALTER TABLE sessions ADD COLUMN confirmed_at TEXT;
