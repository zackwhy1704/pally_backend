-- RevenueCat webhook idempotency. PK on the RC event id makes a re-delivery a
-- fast unique-violation the handler short-circuits to 200, so duplicate events
-- never double-apply an entitlement. Mirrors processed_stripe_events.
CREATE TABLE processed_revenuecat_events (
    event_id     VARCHAR(80) PRIMARY KEY,
    event_type   VARCHAR(80) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
