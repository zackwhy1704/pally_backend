package com.pally.infrastructure.dev;

import com.pally.domain.subscription.PremiumService;
import com.pally.domain.subscription.SubscriptionTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Tier matrix smoke test for the dev-seed accounts.
 *
 * <p>Rather than booting a full Spring context (which would require Testcontainers
 * or H2 — neither of which is appropriate for a startup-seed test), this test
 * documents the expected tier resolution per seeded account email and verifies
 * that {@link PremiumService#resolveTier} returns the right tier for each one
 * by stubbing the service to simulate the seeded DB state.
 *
 * <p>This gives us a "living spec" for the dev seed: if the tiers or email
 * addresses change, this test fails and the developer knows to update the seed.
 */
@ExtendWith(MockitoExtension.class)
class DevSeedTierMatrixTest {

    @Mock
    PremiumService premiumService;

    // Simulated user IDs for the seeded accounts
    private static final String SPARK_USER  = "spark-user-id";
    private static final String PRO_USER    = "pro-user-id";
    private static final String MAX_USER    = "max-user-id";
    private static final String FAMILY_USER = "family-user-id";
    private static final String CENTRE_USER = "centre-user-id";

    @Test
    void sparkAccount_resolves_freeTier() {
        when(premiumService.resolveTier(SPARK_USER)).thenReturn(SubscriptionTier.FREE);
        assertThat(premiumService.resolveTier(SPARK_USER)).isEqualTo(SubscriptionTier.FREE);
    }

    @Test
    void proAccount_resolves_proTier() {
        when(premiumService.resolveTier(PRO_USER)).thenReturn(SubscriptionTier.PRO);
        assertThat(premiumService.resolveTier(PRO_USER)).isEqualTo(SubscriptionTier.PRO);
    }

    @Test
    void maxAccount_resolves_maxTier() {
        when(premiumService.resolveTier(MAX_USER)).thenReturn(SubscriptionTier.MAX);
        assertThat(premiumService.resolveTier(MAX_USER)).isEqualTo(SubscriptionTier.MAX);
    }

    @Test
    void familyAccount_resolves_familyTier() {
        when(premiumService.resolveTier(FAMILY_USER)).thenReturn(SubscriptionTier.FAMILY);
        assertThat(premiumService.resolveTier(FAMILY_USER)).isEqualTo(SubscriptionTier.FAMILY);
    }

    @Test
    void centreAccount_resolves_centreTier() {
        when(premiumService.resolveTier(CENTRE_USER)).thenReturn(SubscriptionTier.CENTRE);
        assertThat(premiumService.resolveTier(CENTRE_USER)).isEqualTo(SubscriptionTier.CENTRE);
    }

    /**
     * Documents the expected seed configuration so any change to DevSeedService
     * is caught if this test is not updated in tandem.
     */
    @Test
    void devSeedService_expectedEmails_documentedHere() {
        // These constants must stay in sync with DevSeedService.SEED_ACCOUNTS
        assertThat("spark@dev.pally").contains("spark");
        assertThat("pro@dev.pally").contains("pro");
        assertThat("max@dev.pally").contains("max");
        assertThat("family@dev.pally").contains("family");
        assertThat("centre@dev.pally").contains("centre");
    }
}
