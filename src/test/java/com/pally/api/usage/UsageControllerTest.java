package com.pally.api.usage;

import com.pally.domain.subscription.PremiumService;
import com.pally.infrastructure.ratelimit.ChatRateLimiter;
import com.pally.shared.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UsageControllerTest {

    @Mock ChatRateLimiter rateLimiter;
    @Mock PremiumService premiumService;
    @InjectMocks UsageController controller;

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
        when(rateLimiter.dailyHitsToday("u1")).thenReturn(0);

        ResponseEntity<ApiResponse<Map<String, Object>>> res = controller.today("u1");
        Map<String, Object> body = res.getBody().data();

        assertThat(body.get("isPremium")).isEqualTo(false);
        assertThat(body.get("chatUsed")).isEqualTo(0);
        assertThat(body.get("chatLimit")).isEqualTo(ChatRateLimiter.FREE_DAILY_LIMIT);
        assertThat(body.get("chatRemaining")).isEqualTo(ChatRateLimiter.FREE_DAILY_LIMIT);
    }

    @Test
    void free_partlyUsed_remainingClampsAtZero() {
        when(premiumService.resolve("u1")).thenReturn(free());
        when(rateLimiter.dailyHitsToday("u1")).thenReturn(25);

        var body = controller.today("u1").getBody().data();
        assertThat(body.get("chatRemaining")).isEqualTo(0);
    }

    @Test
    void premium_limitIsNull() {
        when(premiumService.resolve("u1")).thenReturn(premium());

        var body = controller.today("u1").getBody().data();
        assertThat(body.get("isPremium")).isEqualTo(true);
        assertThat(body.get("chatLimit")).isNull();
        assertThat(body.get("chatRemaining")).isNull();
    }

    /// Resolver failure shouldn't 500 the endpoint — we fall through to
    /// the free-tier surfacing and let the kid see a count anyway.
    @Test
    void resolveFailure_fallsBackToFreeTier() {
        when(premiumService.resolve("u1"))
                .thenThrow(new RuntimeException("redis down"));
        when(rateLimiter.dailyHitsToday("u1")).thenReturn(3);

        var body = controller.today("u1").getBody().data();
        assertThat(body.get("isPremium")).isEqualTo(false);
        assertThat(body.get("chatUsed")).isEqualTo(3);
    }
}
