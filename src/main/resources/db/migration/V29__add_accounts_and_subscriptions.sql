-- Parent / child account model (audit Gap A).
--
-- account_type:
--   SOLO   — a single user account (legacy/default)
--   PARENT — holds the subscription, can link N children
--   CHILD  — uses Pally; learning state lives here; billing on parent
--
-- parent_id    — set on a CHILD account; FK to users(id)
-- link_code    — short claim code a child generates so a parent can attach
-- link_code_expires_at — 24h TTL on the link code
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS account_type VARCHAR(10)
        NOT NULL DEFAULT 'SOLO';

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS parent_id VARCHAR(36)
        REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS link_code VARCHAR(12) UNIQUE;

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS link_code_expires_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_users_parent_id ON users(parent_id);
CREATE INDEX IF NOT EXISTS idx_users_link_code
    ON users(link_code) WHERE link_code IS NOT NULL;

-- Subscriptions live on the PARENT account; the family plan covers all
-- linked children. status mirrors Stripe (active, trialing, past_due,
-- canceled, incomplete, …). When Stripe is not configured the columns
-- can stay NULL and we treat the user as on the free tier.
CREATE TABLE IF NOT EXISTS subscriptions (
    user_id                 VARCHAR(36) PRIMARY KEY
        REFERENCES users(id) ON DELETE CASCADE,
    stripe_customer_id      VARCHAR(80),
    stripe_subscription_id  VARCHAR(80),
    plan                    VARCHAR(40),
    status                  VARCHAR(20) NOT NULL DEFAULT 'free',
    current_period_end      TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
