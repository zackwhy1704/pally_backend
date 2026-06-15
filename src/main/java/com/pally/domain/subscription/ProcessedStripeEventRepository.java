package com.pally.domain.subscription;

import java.time.Instant;

/**
 * Domain port for Stripe webhook idempotency tracking.
 * Implementations live in {@code infrastructure/persistence/subscription}.
 */
public interface ProcessedStripeEventRepository {

    /**
     * Attempts to claim the event by inserting a row with the given id.
     * Returns {@code true} if this is the first delivery (INSERT succeeded),
     * or {@code false} if a PK collision signals a duplicate delivery.
     */
    boolean claimEvent(String eventId, String eventType, Instant processedAt);

    /** Releases a previously claimed event so Stripe's next retry can re-attempt it. */
    void releaseEvent(String eventId);

    record ProcessedEvent(String eventId, String eventType, Instant processedAt) {}
}
