-- Referral system (audit's M5). Codes are lazy-generated on first /me
-- call so existing users don't all get backfilled at once. Same
-- confusable-free alphabet as account link codes.
--
-- referrals.status:
--   pending   — code was redeemed at signup, no reward paid yet
--   activated — referee completed their first quiz → both sides rewarded

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS referral_code VARCHAR(12) UNIQUE;

CREATE INDEX IF NOT EXISTS idx_users_referral_code
    ON users(referral_code) WHERE referral_code IS NOT NULL;

CREATE TABLE IF NOT EXISTS referrals (
    id                 VARCHAR(36) PRIMARY KEY,
    referrer_user_id   VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- Unique so a referee can never be claimed twice. NULL allowed only for
    -- legacy rows; new rows always carry a referee.
    referee_user_id    VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE UNIQUE,
    status             VARCHAR(20) NOT NULL DEFAULT 'pending',
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    activated_at       TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_referrals_referrer
    ON referrals(referrer_user_id);
CREATE INDEX IF NOT EXISTS idx_referrals_status
    ON referrals(referrer_user_id, status);
