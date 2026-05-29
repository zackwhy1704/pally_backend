-- Item 10.1 — email verification + referral fraud gate. We don't block
-- basic app use on unverified email (kids need to start fast), but
-- referral rewards only mint when the referee's email is verified.
-- Anti-fraud: a referrer can't farm by spinning up bogus accounts.

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS email_verified BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE IF NOT EXISTS email_verification_tokens (
    token       VARCHAR(80) PRIMARY KEY,
    user_id     VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at  TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    used_at     TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_email_verification_user
    ON email_verification_tokens(user_id);
