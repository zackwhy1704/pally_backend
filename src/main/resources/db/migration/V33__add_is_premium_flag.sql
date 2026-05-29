-- users.is_premium is a denormalised cache so a frontend gate doesn't have to
-- JOIN through subscriptions on every request. PremiumService is the single
-- source of truth and refreshes this column when it computes entitlement.
-- Webhooks set it directly on subscription-status changes.

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS is_premium BOOLEAN NOT NULL DEFAULT FALSE;
