package com.pally.infrastructure.persistence.subscription;

import com.pally.domain.subscription.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * JPA adapter that satisfies the {@link SubscriptionRepository} domain port.
 */
@Component
@RequiredArgsConstructor
public class SubscriptionRepositoryAdapter implements SubscriptionRepository {

    private final SubscriptionJpaRepository jpa;

    @Override
    public Optional<Subscription> findById(String userId) {
        return jpa.findById(userId).map(this::toDomain);
    }

    @Override
    public Subscription save(Subscription s) {
        SubscriptionJpaEntity e = jpa.findById(s.userId())
                .orElseGet(() -> {
                    SubscriptionJpaEntity created = new SubscriptionJpaEntity();
                    created.setUserId(s.userId());
                    return created;
                });
        e.setStripeCustomerId(s.stripeCustomerId());
        e.setStripeSubscriptionId(s.stripeSubscriptionId());
        e.setPlan(s.plan());
        e.setStatus(s.status() != null ? s.status() : "free");
        e.setCurrentPeriodEnd(s.currentPeriodEnd());
        e.setCancelAtPeriodEnd(s.cancelAtPeriodEnd());
        e.setCanceledAt(s.canceledAt());
        if (s.updatedAt() != null) e.setUpdatedAt(s.updatedAt());
        // Only ever ADVANCES. A caller passing null (the Stripe path, which verified
        // nothing with RevenueCat) must not erase a real verification timestamp —
        // wiping it would make a fresh row look stale and trigger a pointless
        // re-check, or worse, read as never-verified.
        if (s.lastVerifiedAt() != null) e.setLastVerifiedAt(s.lastVerifiedAt());
        jpa.save(e);
        return toDomain(e);
    }

    @Override
    public Optional<String> findUserIdByStripeSubscriptionId(String stripeSubId) {
        if (stripeSubId == null) return Optional.empty();
        return jpa.findAll().stream()
                .filter(r -> stripeSubId.equals(r.getStripeSubscriptionId()))
                .map(SubscriptionJpaEntity::getUserId)
                .findFirst();
    }

    private Subscription toDomain(SubscriptionJpaEntity e) {
        return new Subscription(
                e.getUserId(), e.getStripeCustomerId(), e.getStripeSubscriptionId(),
                e.getPlan(), e.getStatus(), e.getCurrentPeriodEnd(),
                e.isCancelAtPeriodEnd(), e.getCanceledAt(),
                e.getCreatedAt(), e.getUpdatedAt(),
                e.getLastVerifiedAt()
        );
    }
}
