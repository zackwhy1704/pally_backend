-- V54: Subscription tier computed column + cancellation tracking
--
-- Adds:
--   subscriptions.tier           — STORED computed column from plan string
--   subscriptions.canceled_at    — timestamp of subscription.deleted event
--   subscriptions.cancel_at_period_end — Stripe flag: sub stays active until period end

ALTER TABLE subscriptions
    ADD COLUMN IF NOT EXISTS canceled_at TIMESTAMPTZ;

ALTER TABLE subscriptions
    ADD COLUMN IF NOT EXISTS cancel_at_period_end BOOLEAN NOT NULL DEFAULT FALSE;

-- Generated STORED column: derive the tier enum value from the plan string.
-- CENTRE > FAMILY > MAX > PRO > FREE (longest-match wins via CASE order).
-- Uses LIKE '%centre%' so both centre_monthly and centre_annual resolve correctly.
ALTER TABLE subscriptions
    ADD COLUMN IF NOT EXISTS tier VARCHAR(10)
        GENERATED ALWAYS AS (
          CASE
            WHEN plan LIKE '%centre%'  THEN 'CENTRE'
            WHEN plan LIKE '%family%'  THEN 'FAMILY'
            WHEN plan LIKE '%max%'     THEN 'MAX'
            WHEN plan LIKE '%pro%'     THEN 'PRO'
            ELSE 'FREE'
          END
        ) STORED;
