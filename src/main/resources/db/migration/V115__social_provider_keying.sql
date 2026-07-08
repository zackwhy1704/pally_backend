-- AUTH HARDENING A (Phase 3b) — sub-keyed social identity + all-session invalidation.
--
-- Social accounts were keyed purely on EMAIL (no stable provider subject), so a changed
-- email or a relay/real-email switch broke identity and enabled email-based auto-link.
-- Add the provider identity: (provider, provider_sub). New social accounts are keyed on
-- it from creation; legacy social rows are LAZILY backfilled (matched by a VERIFIED
-- email once, then stamped with the sub and sub-keyed thereafter).
ALTER TABLE users ADD COLUMN provider     VARCHAR(20)  NULL;
ALTER TABLE users ADD COLUMN provider_sub VARCHAR(255) NULL;

-- One account per (provider, sub). Partial (only when a sub is present) so the vast
-- majority of rows (password accounts, un-backfilled legacy social) are unaffected.
CREATE UNIQUE INDEX IF NOT EXISTS uq_users_provider_sub
    ON users (provider, provider_sub) WHERE provider_sub IS NOT NULL;

-- Session epoch for "invalidate ALL sessions" (account linking, password reset). A JWT
-- carries the epoch it was minted under; the auth filter rejects any token whose epoch is
-- below the user's current epoch. Bumping the epoch invalidates every outstanding token.
ALTER TABLE users ADD COLUMN session_epoch INT NOT NULL DEFAULT 0;
