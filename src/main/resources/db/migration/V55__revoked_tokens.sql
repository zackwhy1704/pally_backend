-- V55: Revoked JWT tokens table for account-deletion token invalidation.
--
-- Adds:
--   revoked_tokens (jti, revoked_at, expires_at)
--
-- Rows are inserted when a user deletes their account, making their JWT
-- immediately invalid even though it hasn't expired yet.
-- The JWT filter checks this table on every request.
--
-- Cleanup: rows whose expires_at < NOW() can be pruned safely (the token
-- would be rejected by the JWT expiry check regardless). Run periodically:
--   DELETE FROM revoked_tokens WHERE expires_at < NOW();

CREATE TABLE IF NOT EXISTS revoked_tokens (
    jti        VARCHAR(36)  PRIMARY KEY,
    revoked_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ  NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_revoked_tokens_expires
    ON revoked_tokens (expires_at);
