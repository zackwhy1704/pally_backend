package com.pally.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.pally.domain.subscription.SubscriptionRepository;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The production admin comp must survive the RevenueCat work untouched.
 *
 * <p>There is exactly one row in production `subscriptions`, and it is NOT a
 * paying subscriber: {@code plan='admin'}, {@code tier='FREE'}, synthetic
 * {@code admin_by...} ids, {@code current_period_end=2099-12-31}. A naive count
 * reports it as "1 active subscription with a Stripe subscription id", which is
 * how it would get mistaken for a real customer.
 *
 * <p>Two ways this pass could have broken it, both asserted below: treating the
 * synthetic id as a real Stripe subscription, and stamping or wiping
 * {@code last_verified_at} on a row that RevenueCat has never seen and never will.
 */
class AdminCompSurvivesIapChangesTest extends IntegrationTestBase {

    @Autowired private SubscriptionRepository subscriptionRepo;

    private static final Instant SENTINEL_END =
            ZonedDateTime.of(2099, 12, 31, 0, 0, 0, 0, ZoneOffset.UTC).toInstant();

    private String seedAdminComp() {
        String userId = newUserRow();
        jdbcTemplate.update(
                // `tier` is a GENERATED ALWAYS column derived from `plan` (V54) and
                // cannot be inserted. plan='admin' matches none of the paid patterns,
                // so it computes to 'FREE' — which is exactly why the comp grants
                // premium via the ADMIN role rather than via a tier.
                "INSERT INTO subscriptions (user_id, stripe_customer_id, stripe_subscription_id, "
                        + "plan, status, current_period_end, cancel_at_period_end, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'admin', 'active', ?, false, now(), now())",
                userId, "admin_by_bootstrap", "admin_by_bootstrap",
                java.sql.Timestamp.from(SENTINEL_END));
        return userId;
    }

    @Test
    void theAdminComp_isNotTreatedAsARevenueCatRow() {
        String userId = seedAdminComp();

        Optional<SubscriptionRepository.Subscription> found = subscriptionRepo.findById(userId);

        assertThat(found).isPresent();
        assertThat(found.get().plan()).isEqualTo("admin");
        assertThat(found.get().status()).isEqualTo("active");
        String tier = jdbcTemplate.queryForObject(
                "SELECT tier FROM subscriptions WHERE user_id = ?", String.class, userId);
        assertThat(tier)
                .as("plan='admin' matches no paid pattern, so the generated tier is FREE")
                .isEqualTo("FREE");
        assertThat(found.get().lastVerifiedAt())
                .as("RevenueCat has never verified this row, so it must stay NULL — "
                        + "stamping it would claim a verification that never happened")
                .isNull();
    }

    @Test
    void savingTheAdminComp_withNullVerification_doesNotWipeOrInventOne() {
        // The adapter only ever ADVANCES last_verified_at. A caller passing null
        // (the Stripe path, which verified nothing with RevenueCat) must neither
        // erase an existing timestamp nor fabricate one.
        String userId = seedAdminComp();
        SubscriptionRepository.Subscription comp = subscriptionRepo.findById(userId).orElseThrow();

        subscriptionRepo.save(new SubscriptionRepository.Subscription(
                comp.userId(), comp.stripeCustomerId(), comp.stripeSubscriptionId(),
                comp.plan(), comp.status(), comp.currentPeriodEnd(),
                comp.cancelAtPeriodEnd(), comp.canceledAt(), comp.createdAt(),
                Instant.now(), null));

        SubscriptionRepository.Subscription after = subscriptionRepo.findById(userId).orElseThrow();
        assertThat(after.lastVerifiedAt()).isNull();
        assertThat(after.plan()).isEqualTo("admin");
        assertThat(after.currentPeriodEnd()).isEqualTo(SENTINEL_END);
    }

    @Test
    void anExistingVerification_isNeverWipedByANullWrite() {
        // The other direction: a genuinely RevenueCat-verified row must keep its
        // timestamp when an unrelated write passes null, or it would read as stale
        // and trigger a needless re-check.
        String userId = newUserRow();
        Instant verified = Instant.now();
        subscriptionRepo.save(new SubscriptionRepository.Subscription(
                userId, null, null, "pro", "active", Instant.now().plusSeconds(86_400),
                false, null, Instant.now(), Instant.now(), verified));

        SubscriptionRepository.Subscription s = subscriptionRepo.findById(userId).orElseThrow();
        subscriptionRepo.save(new SubscriptionRepository.Subscription(
                s.userId(), s.stripeCustomerId(), s.stripeSubscriptionId(), s.plan(),
                s.status(), s.currentPeriodEnd(), s.cancelAtPeriodEnd(), s.canceledAt(),
                s.createdAt(), Instant.now(), null));

        assertThat(subscriptionRepo.findById(userId).orElseThrow().lastVerifiedAt())
                .as("a null write must not erase a real verification")
                .isNotNull();
    }
}
