package com.pally.domain.subscription;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the per-tier family child cap logic in
 * {@link SubscriptionLimits#familyChildCap}.
 *
 * <p>The cap controls how many children a parent may link. Exceeding it
 * would let one family subscription cover more users than the plan allows.
 */
class FamilyChildCapTest {

    @Test
    void familyTier_capIsFour() {
        assertThat(SubscriptionLimits.familyChildCap(SubscriptionTier.FAMILY)).isEqualTo(4);
    }

    @Test
    void freeTier_capIsZero() {
        assertThat(SubscriptionLimits.familyChildCap(SubscriptionTier.FREE)).isEqualTo(0);
    }

    @Test
    void proTier_capIsZero() {
        assertThat(SubscriptionLimits.familyChildCap(SubscriptionTier.PRO)).isEqualTo(0);
    }

    @Test
    void maxTier_capIsZero() {
        assertThat(SubscriptionLimits.familyChildCap(SubscriptionTier.MAX)).isEqualTo(0);
    }

    // ── Enforcement simulation ────────────────────────────────────────────

    @Test
    void familyTier_fourthChildAllowed_fifthBlocked() {
        int cap = SubscriptionLimits.familyChildCap(SubscriptionTier.FAMILY);
        // 3 children already — adding a 4th should be allowed (3 < 4)
        assertThat(3).isLessThan(cap);
        // 4 children already — adding a 5th should be blocked (4 >= 4)
        assertThat(4).isGreaterThanOrEqualTo(cap);
    }

}
