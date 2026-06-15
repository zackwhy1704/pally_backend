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
            Instant updatedAt
    ) {}
}
