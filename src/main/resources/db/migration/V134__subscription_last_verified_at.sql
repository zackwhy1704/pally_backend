-- ENTITLEMENT STALENESS BOUND for the RevenueCat/IAP path.
--
-- WHY THIS COLUMN EXISTS
-- Entitlement is server-authoritative: the client never tells the server what it
-- paid for (the same principle as V97 quiz grading). The backend learns about
-- purchases from RevenueCat webhooks and writes them to `subscriptions`.
--
-- That leaves one real failure mode: a MISSED webhook. If RevenueCat cannot
-- deliver, our row silently goes stale and we keep serving an entitlement that
-- may no longer be true — indefinitely, because nothing records how old our
-- knowledge is. `last_verified_at` is that timestamp.
--
-- Read semantics (>= 24h is STALE, inclusive):
--   * server: a row whose last_verified_at is >= 24h old is not trusted on its
--     own; the entitlement is re-checked against RevenueCat's REST API.
--   * client: pally caches the last known entitlement and honours it for 24h
--     from this value when the backend is unreachable, then fails CLOSED.
-- Together these make the fail-open BOUNDED: a blocked webhook can extend a
-- cancelled subscription's access by at most one day, not forever.
--
-- NULLABLE ON PURPOSE. Existing rows predate RevenueCat and were never verified
-- against it; back-filling now() would be a lie that claims fresh verification
-- for rows nobody checked. NULL reads as "never verified", which the resolver
-- treats as stale-but-not-revoked so no current access is disturbed by this
-- migration. In particular the admin comp (plan='admin', synthetic
-- 'admin_by...' ids, current_period_end 2099-12-31) is NOT a RevenueCat row and
-- must never be re-verified against or revoked by it.

ALTER TABLE subscriptions
    ADD COLUMN IF NOT EXISTS last_verified_at TIMESTAMPTZ;

-- The staleness sweep reads oldest-first.
CREATE INDEX IF NOT EXISTS idx_subscriptions_last_verified_at
    ON subscriptions (last_verified_at);
