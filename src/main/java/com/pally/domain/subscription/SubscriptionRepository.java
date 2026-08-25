package com.pally.domain.subscription;

import java.time.Instant;
import java.util.Optional;

/**
 * Domain port for subscription persistence.
 * Implementations live in {@code infrastructure/persistence/subscription}.
 */
public interface SubscriptionRepository {

    /** Returns the subscription for the given user, or empty if none exists. */
    Optional<Subscription> findById(String userId);

    /** Persists the subscription and returns the saved instance. */
    Subscription save(Subscription subscription);

    /**
     * Finds the userId that owns the given Stripe subscription id, by
     * scanning the subscriptions table.
     */
    Optional<String> findUserIdByStripeSubscriptionId(String stripeSubscriptionId);

    record Subscription(
            String userId,
            String stripeCustomerId,
            String stripeSubscriptionId,
            String plan,
            String status,
            Instant currentPeriodEnd,
            boolean cancelAtPeriodEnd,
            Instant canceledAt,
            Instant createdAt,
            Instant updatedAt,
            /**
             * When this row was last CONFIRMED against the payment provider
             * (a RevenueCat webhook apply or REST re-check).
             *
             * <p>NULL means never verified — legacy rows and the admin comp. Read
             * as stale-but-not-revoked: it triggers a re-check for RevenueCat-backed
             * rows and is ignored for rows that were never RevenueCat's to begin with.
             * A row is STALE when {@code now - lastVerifiedAt >= 24h} (inclusive).
             */
            Instant lastVerifiedAt
    ) {}
}
