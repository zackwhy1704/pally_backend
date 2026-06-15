package com.pally.domain.subscription;

import com.pally.domain.account.AccountType;
import com.pally.domain.user.User;
import com.pally.domain.user.UserRepository;
import com.pally.infrastructure.persistence.subscription.SubscriptionJpaEntity;
import com.pally.infrastructure.persistence.subscription.SubscriptionJpaRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link PremiumService#resolveTier} maps every plan string to
 * the correct {@link SubscriptionTier}.
 *
 * <p>This is a money-path test: a wrong tier hands out the wrong chat limit,
 * Mochi cap, and model routing — high blast radius.
 */
@ExtendWith(MockitoExtension.class)
class SubscriptionTierResolutionTest {

    @Mock UserRepository userRepo;
    @Mock SubscriptionJpaRepository subRepo;

    @InjectMocks PremiumService premiumService;

    // ── Helpers ──────────────────────────────────────────────────────────────

    private User soloUser(String id) {
        User u = new User();
        u.setId(id);
        u.setAccountType(AccountType.SOLO);
        return u;
    }

    private SubscriptionJpaEntity activeSub(String userId, String plan) {
        SubscriptionJpaEntity s = new SubscriptionJpaEntity();
        s.setUserId(userId);
        s.setStatus("active");
        s.setPlan(plan);
        s.setCurrentPeriodEnd(Instant.now().plusSeconds(86_400));
        return s;
    }

    // ── Tier resolution tests ─────────────────────────────────────────────

    @Test
    void resolveTier_noSubscription_returnsFree() {
        when(userRepo.findById("u1")).thenReturn(Optional.of(soloUser("u1")));
        when(subRepo.findById("u1")).thenReturn(Optional.empty());

        assertThat(premiumService.resolveTier("u1")).isEqualTo(SubscriptionTier.FREE);
    }

    @Test
    void resolveTier_proMonthlyPlan_returnsPro() {
        when(userRepo.findById("u2")).thenReturn(Optional.of(soloUser("u2")));
        when(subRepo.findById("u2")).thenReturn(Optional.of(activeSub("u2", "pro_monthly")));

        assertThat(premiumService.resolveTier("u2")).isEqualTo(SubscriptionTier.PRO);
    }

    @Test
    void resolveTier_proAnnualPlan_returnsPro() {
        when(userRepo.findById("u3")).thenReturn(Optional.of(soloUser("u3")));
        when(subRepo.findById("u3")).thenReturn(Optional.of(activeSub("u3", "pro_annual")));

        assertThat(premiumService.resolveTier("u3")).isEqualTo(SubscriptionTier.PRO);
    }

    @Test
    void resolveTier_maxMonthlyPlan_returnsMax() {
        when(userRepo.findById("u4")).thenReturn(Optional.of(soloUser("u4")));
        when(subRepo.findById("u4")).thenReturn(Optional.of(activeSub("u4", "max_monthly")));

        assertThat(premiumService.resolveTier("u4")).isEqualTo(SubscriptionTier.MAX);
    }

    @Test
    void resolveTier_familyAnnualPlan_returnsFamily() {
        when(userRepo.findById("u5")).thenReturn(Optional.of(soloUser("u5")));
        when(subRepo.findById("u5")).thenReturn(Optional.of(activeSub("u5", "family_annual")));

        assertThat(premiumService.resolveTier("u5")).isEqualTo(SubscriptionTier.FAMILY);
    }

    @Test
    void resolveTier_centreMonthlyPlan_returnsCentre() {
        when(userRepo.findById("u6")).thenReturn(Optional.of(soloUser("u6")));
        when(subRepo.findById("u6")).thenReturn(Optional.of(activeSub("u6", "centre_monthly")));

        assertThat(premiumService.resolveTier("u6")).isEqualTo(SubscriptionTier.CENTRE);
    }

    @Test
    void resolveTier_legacyIndividualMonthlyPlan_returnsMax() {
        // Legacy plans without pro/max/family/centre → treated as MAX
        when(userRepo.findById("u7")).thenReturn(Optional.of(soloUser("u7")));
        when(subRepo.findById("u7")).thenReturn(Optional.of(activeSub("u7", "individual_monthly")));

        assertThat(premiumService.resolveTier("u7")).isEqualTo(SubscriptionTier.MAX);
    }

    @Test
    void resolveTier_cancelledSubscription_returnsFree() {
        when(userRepo.findById("u8")).thenReturn(Optional.of(soloUser("u8")));
        SubscriptionJpaEntity cancelled = activeSub("u8", "max_monthly");
        cancelled.setStatus("canceled");
        when(subRepo.findById("u8")).thenReturn(Optional.of(cancelled));

        // Cancelled → not premium → FREE tier
        assertThat(premiumService.resolveTier("u8")).isEqualTo(SubscriptionTier.FREE);
    }

    @Test
    void resolveTier_unknownUser_returnsFree() {
        when(userRepo.findById("ghost")).thenReturn(Optional.empty());

        assertThat(premiumService.resolveTier("ghost")).isEqualTo(SubscriptionTier.FREE);
    }

    @Test
    void resolveTier_centreWinsPrecedenceOverFamily() {
        // Hypothetical plan that contains both "centre" and "family" — CENTRE wins
        when(userRepo.findById("u9")).thenReturn(Optional.of(soloUser("u9")));
        when(subRepo.findById("u9")).thenReturn(Optional.of(activeSub("u9", "centre_family_plan")));

        assertThat(premiumService.resolveTier("u9")).isEqualTo(SubscriptionTier.CENTRE);
    }

    @Test
    void resolveTier_adminPlan_returnsMax() {
        when(userRepo.findById("ua")).thenReturn(Optional.of(soloUser("ua")));
        when(subRepo.findById("ua")).thenReturn(Optional.of(activeSub("ua", "admin")));

        assertThat(premiumService.resolveTier("ua")).isEqualTo(SubscriptionTier.MAX);
    }
}
