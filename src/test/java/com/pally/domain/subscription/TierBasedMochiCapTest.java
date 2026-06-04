package com.pally.domain.subscription;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the per-tier Mochi cap logic in {@link SubscriptionLimits#mochiCap}.
 *
 * <p>Mochi cap controls how many AI tutors a user may create. Getting it wrong
 * in either direction is a product bug: too restrictive blocks paying users,
 * too permissive gives free users more than their entitlement.
 */
class TierBasedMochiCapTest {

    // ── FREE tier (level-gated) ───────────────────────────────────────────

    @Test
    void freeTier_belowL5_capIsOne() {
        assertThat(SubscriptionLimits.mochiCap(SubscriptionTier.FREE, 1)).isEqualTo(1);
        assertThat(SubscriptionLimits.mochiCap(SubscriptionTier.FREE, 4)).isEqualTo(1);
    }

    @Test
    void freeTier_atL5_capIsTwo() {
        assertThat(SubscriptionLimits.mochiCap(SubscriptionTier.FREE, 5)).isEqualTo(2);
        assertThat(SubscriptionLimits.mochiCap(SubscriptionTier.FREE, 10)).isEqualTo(2);
    }

    @Test
    void freeTier_highLevel_capRemainsTwo() {
        // FREE tier cap doesn't grow beyond 2 regardless of level
        assertThat(SubscriptionLimits.mochiCap(SubscriptionTier.FREE, 30)).isEqualTo(2);
    }

    // ── PRO tier ─────────────────────────────────────────────────────────

    @Test
    void proTier_anyLevel_capIsFive() {
        assertThat(SubscriptionLimits.mochiCap(SubscriptionTier.PRO, 1)).isEqualTo(5);
        assertThat(SubscriptionLimits.mochiCap(SubscriptionTier.PRO, 5)).isEqualTo(5);
        assertThat(SubscriptionLimits.mochiCap(SubscriptionTier.PRO, 20)).isEqualTo(5);
    }

    // ── MAX / FAMILY / CENTRE tier (unlimited) ────────────────────────────

    @Test
    void maxTier_anyLevel_capIsUnlimited() {
        assertThat(SubscriptionLimits.mochiCap(SubscriptionTier.MAX, 1))
                .isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void familyTier_anyLevel_capIsUnlimited() {
        assertThat(SubscriptionLimits.mochiCap(SubscriptionTier.FAMILY, 1))
                .isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void centreTier_anyLevel_capIsUnlimited() {
        assertThat(SubscriptionLimits.mochiCap(SubscriptionTier.CENTRE, 1))
                .isEqualTo(Integer.MAX_VALUE);
    }

    // ── Boundary: free user at exactly L5 can create a second Mochi ──────

    @Test
    void freeTier_atL5_secondMochiIsAllowed() {
        int cap = SubscriptionLimits.mochiCap(SubscriptionTier.FREE, 5);
        int existingCount = 1;
        assertThat(existingCount).isLessThan(cap);
    }

    @Test
    void freeTier_atL4_secondMochiIsBlocked() {
        int cap = SubscriptionLimits.mochiCap(SubscriptionTier.FREE, 4);
        int existingCount = 1;
        assertThat(existingCount).isGreaterThanOrEqualTo(cap);
    }
}
