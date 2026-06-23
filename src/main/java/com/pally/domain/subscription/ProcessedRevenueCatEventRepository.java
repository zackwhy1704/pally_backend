package com.pally.domain.subscription;

import java.time.Instant;

/**
 * Domain port for RevenueCat webhook idempotency tracking. Mirrors
 * {@link ProcessedStripeEventRepository}. Implementations live in
 * {@code infrastructure/persistence/subscription}.
 */
public interface ProcessedRevenueCatEventRepository {

    /**
     * Attempts to claim the event by inserting a row with the given id.
     * Returns {@code true} if this is the first delivery (INSERT succeeded),
     * or {@code false} if a PK collision signals a duplicate delivery.
     */
    boolean claimEvent(String eventId, String eventType, Instant processedAt);

    /** Releases a previously claimed event so a later retry can re-attempt it. */
    void releaseEvent(String eventId);
}
