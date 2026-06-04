package com.pally.api.usage;

import com.pally.domain.subscription.PremiumService;
import com.pally.domain.subscription.SubscriptionTier;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.infrastructure.ratelimit.ChatRateLimiter;
import com.pally.shared.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UsageControllerTest {

    @Mock ChatRateLimiter rateLimiter;
    @Mock PremiumService premiumService;
    @Mock UserJpaRepository userRepo;
    @InjectMocks UsageController controller;

    private static final PremiumService.TrialInfo NO_TRIAL =
            new PremiumService.TrialInfo(false, null, 0, 0, PremiumService.TRIAL_NONE);

    @BeforeEach
    void stubDefaults() {
        // Default: no trial, level-1 user. Individual tests override as needed.
        when(premiumService.getTrialInfo(anyString())).thenReturn(NO_TRIAL);
        var user = new com.pally.infrastructure.persistence.progress.UserJpaEntity();
        user.setLevel(1);
        when(userRepo.findById(anyString())).thenReturn(Optional.of(user));
    }

    private PremiumService.Entitlement free() {
        return new PremiumService.Entitlement(false, "NONE", null, "free", null);
    }

    private PremiumService.Entitlement premium() {
        return new PremiumService.Entitlement(
                true, "SELF", "family_monthly", "active", null);
    }

    @Test
    void free_unused_shows20OfTwentyLeft() {
        when(premiumService.resolve("u1")).thenReturn(free());
        when(premiumService.resolveTier("u1")).thenReturn(SubscriptionTier.FREE);
        when(rateLimiter.dailyHitsToday("u1")).thenReturn(0);

        ResponseEntity<ApiResponse<Map<String, Object>>> res = controller.today("u1");
        Map<String, Object> body = res.getBody().data();

        assertThat(body.get("isPremium")).isEqualTo(false);
        assertThat(body.get("subscriptionTier")).isEqualTo("FREE");
        assertThat(body.get("chatUsed")).isEqualTo(0);
        assertThat(body.get("chatLimit")).isEqualTo(ChatRateLimiter.FREE_DAILY_LIMIT);
        assertThat(body.get("chatRemaining")).isEqualTo(ChatRateLimiter.FREE_DAILY_LIMIT);
        assertThat(body.get("mochiCap")).isEqualTo(1); // level 1
    }

    @Test
    void free_partlyUsed_remainingClampsAtZero() {
        when(premiumService.resolve("u1")).thenReturn(free());
        when(premiumService.resolveTier("u1")).thenReturn(SubscriptionTier.FREE);
        when(rateLimiter.dailyHitsToday("u1")).thenReturn(85);

        var body = controller.today("u1").getBody().data();
        assertThat(body.get("chatRemaining")).isEqualTo(0);
    }

    @Test
    void pro_limitIs100() {
        when(premiumService.resolve("u1")).thenReturn(
                new PremiumService.Entitlement(true, "SELF", "pro_monthly", "active", null));
        when(premiumService.resolveTier("u1")).thenReturn(SubscriptionTier.PRO);
        when(rateLimiter.dailyHitsToday("u1")).thenReturn(10);

        var body = controller.today("u1").getBody().data();
        assertThat(body.get("subscriptionTier")).isEqualTo("PRO");
        assertThat(body.get("chatLimit")).isEqualTo(ChatRateLimiter.PRO_DAILY_LIMIT);
        assertThat(body.get("chatRemaining")).isEqualTo(ChatRateLimiter.PRO_DAILY_LIMIT - 10);
        assertThat(body.get("mochiCap")).isEqualTo(5);
    }

    @Test
    void max_limitIsUnlimited_signalledByMinusOne() {
        when(premiumService.resolve("u1")).thenReturn(
                new PremiumService.Entitlement(true, "SELF", "max_monthly", "active", null));
        when(premiumService.resolveTier("u1")).thenReturn(SubscriptionTier.MAX);

        var body = controller.today("u1").getBody().data();
        assertThat(body.get("isPremium")).isEqualTo(true);
        assertThat(body.get("subscriptionTier")).isEqualTo("MAX");
        assertThat(body.get("chatLimit")).isEqualTo(-1);
        assertThat(body.get("chatRemaining")).isEqualTo(-1);
        assertThat(body.get("mochiCap")).isEqualTo(-1); // unlimited
    }

    @Test
    void family_limitIsUnlimited_signalledByMinusOne() {
        when(premiumService.resolve("u1")).thenReturn(premium());
        when(premiumService.resolveTier("u1")).thenReturn(SubscriptionTier.FAMILY);

        var body = controller.today("u1").getBody().data();
        assertThat(body.get("subscriptionTier")).isEqualTo("FAMILY");
        assertThat(body.get("chatLimit")).isEqualTo(-1);
    }

    /// Resolver failure shouldn't 500 the endpoint — we fall through to
    /// the free-tier surfacing and let the kid see a count anyway.
    @Test
    void resolveFailure_fallsBackToFreeTier() {
        when(premiumService.resolve("u1"))
                .thenThrow(new RuntimeException("redis down"));
        // resolveTier also wraps in try/catch in controller — stub as throwing
        when(premiumService.resolveTier("u1"))
                .thenThrow(new RuntimeException("redis down"));
        when(rateLimiter.dailyHitsToday("u1")).thenReturn(3);

        var body = controller.today("u1").getBody().data();
        assertThat(body.get("isPremium")).isEqualTo(false);
        assertThat(body.get("chatUsed")).isEqualTo(3);
        // Falls back to FREE tier
        assertThat(body.get("subscriptionTier")).isEqualTo("FREE");
    }
}
