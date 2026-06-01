-- Cardless 7-day Premium trial. Distinct from Stripe trial (which needs a
-- card). PremiumService treats TRIAL source as premium between SELF and NONE.
-- trial_status: NONE | ACTIVE | EXPIRED | CONVERTED
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS trial_started_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS trial_ends_at    TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS trial_status     VARCHAR(20) NOT NULL DEFAULT 'NONE';

-- Existing users are past signup; they do not get a retroactive trial.
UPDATE users SET trial_status = 'NONE' WHERE trial_status IS NULL;

CREATE INDEX IF NOT EXISTS idx_users_trial_status ON users(trial_status)
    WHERE trial_status = 'ACTIVE';
