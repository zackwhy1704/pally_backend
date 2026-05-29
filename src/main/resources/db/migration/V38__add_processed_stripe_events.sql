-- Item 10.2 — Stripe webhook idempotency. Stripe retries aggressively on
-- any non-2xx; we already return 200-on-handler-failure to short-circuit
-- that, but a real retry storm (e.g. a brief outage) can replay the same
-- event after we processed it. Dedupe by event.id so a re-delivery is
-- a no-op rather than a double XP credit or double subscription flip.

CREATE TABLE IF NOT EXISTS processed_stripe_events (
    event_id     VARCHAR(80) PRIMARY KEY,
    event_type   VARCHAR(80) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
