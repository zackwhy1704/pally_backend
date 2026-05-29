package com.pally.domain.subscription;

import com.pally.infrastructure.config.CacheConfig;
import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.infrastructure.persistence.subscription.SubscriptionJpaEntity;
import com.pally.infrastructure.persistence.subscription.SubscriptionJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;

/**
 * Single source of truth for premium entitlement.
 *
 * <p>A user is considered premium if:
 *  - their own subscriptions row has {@code status ∈ ACTIVE_STATUSES}, OR
 *  - they are a CHILD whose parent's subscription is active, OR
 *  - (M6, when wired) their centre's subscription is active.
 *
 * <p>{@link #resolve} also refreshes the denormalised {@code users.is_premium}
 * cache so a follow-up call from any other path (chat gating, paywall guard)
 * can read straight off the user row without re-resolving the family graph.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PremiumService {

    private static final Set<String> ACTIVE_STATUSES = Set.of("active", "trialing");

    private final UserJpaRepository userRepo;
    private final SubscriptionJpaRepository subRepo;

    public record Entitlement(
            boolean isPremium,
            String source,       // SELF | PARENT | CENTRE | NONE
            String plan,
            String status,
            Instant trialEndsAt
    ) {}

    /// Cached: same userId hits Postgres at most once per ENTITLEMENT TTL
    /// (60s). Eviction fires on every premium-write path (Stripe webhook,
    /// family link change) so a cancellation can't be hidden by a stale
    /// cache for more than that TTL even if eviction is missed.
    @Cacheable(value = CacheConfig.ENTITLEMENT, key = "#userId")
    @Transactional
    public Entitlement resolve(String userId) {
        UserJpaEntity user = userRepo.findById(userId).orElse(null);
        if (user == null) {
            return new Entitlement(false, "NONE", null, "free", null);
        }

        // Self check first — own subscription wins over inherited so the
        // displayed plan label matches what the user is paying for.
        SubscriptionJpaEntity own = subRepo.findById(userId).orElse(null);
        if (own != null && ACTIVE_STATUSES.contains(own.getStatus())) {
            persistFlag(user, true);
            return new Entitlement(true, "SELF",
                    own.getPlan(), own.getStatus(),
                    isTrialing(own) ? own.getCurrentPeriodEnd() : null);
        }

        // Inherit from parent for CHILD accounts.
        if ("CHILD".equals(user.getAccountType()) && user.getParentId() != null) {
            SubscriptionJpaEntity parentSub =
                    subRepo.findById(user.getParentId()).orElse(null);
            if (parentSub != null && ACTIVE_STATUSES.contains(parentSub.getStatus())) {
                persistFlag(user, true);
                return new Entitlement(true, "PARENT",
                        parentSub.getPlan(), parentSub.getStatus(),
                        isTrialing(parentSub) ? parentSub.getCurrentPeriodEnd() : null);
            }
        }

        // No active subscription. Don't expose the cancelled-plan label so the
        // UI doesn't have to special-case "premium=false, plan=family_monthly".
        persistFlag(user, false);
        String status = own != null ? own.getStatus() : "free";
        return new Entitlement(false, "NONE", null, status, null);
    }

    /// Force-refresh the user's flag — call from webhook handlers when the
    /// subscription state changes asynchronously. Evicts the cached
    /// entitlement entry first so the resolve() call inside doesn't
    /// hand back the stale value via @Cacheable's read-through.
    @CacheEvict(value = CacheConfig.ENTITLEMENT, key = "#userId")
    @Transactional
    public void refreshFlag(String userId) {
        resolve(userId);
    }

    /// Public eviction hook for non-subscription writes that still need
    /// to flush the entitlement cache (e.g. parent-link claim flips a
    /// CHILD's inherited premium status).
    @CacheEvict(value = CacheConfig.ENTITLEMENT, key = "#userId")
    public void evictEntitlement(String userId) {
        // Method body is empty by design — the annotation does the work.
    }

    private void persistFlag(UserJpaEntity user, boolean isPremium) {
        if (user.isPremium() == isPremium) return;
        user.setPremium(isPremium);
        userRepo.save(user);
        log.info("[Premium] user={} is_premium={}", user.getId(), isPremium);
    }

    private boolean isTrialing(SubscriptionJpaEntity sub) {
        return "trialing".equals(sub.getStatus());
    }
}
