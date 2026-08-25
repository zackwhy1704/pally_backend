package com.pally.infrastructure.ratelimit;

import com.pally.domain.subscription.ChatQuotaProperties;
import com.pally.domain.subscription.PremiumService;
import com.pally.domain.subscription.SubscriptionTier;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.exception.UpgradeRequiredException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Edge-case tests for ChatRateLimiter: window boundary, burst at limit,
 * one-over triggering 429.
 */
@ExtendWith(MockitoExtension.class)
class ChatRateLimiterEdgeCaseTest {

    @Mock PremiumService premiumService;

    /// Real properties rather than a mock: these tests are ABOUT the caps, so a
    /// stubbed quota would let the limiter and the assertions drift apart.
    private static final ChatQuotaProperties QUOTAS = new ChatQuotaProperties();

    private ChatRateLimiter newLimiter() {
        return new ChatRateLimiter(premiumService, QUOTAS);
    }

    @Test
    void freeUser_exactlyAtDailyLimit_doesNotThrow() {
        when(premiumService.resolveTier("u1")).thenReturn(SubscriptionTier.FREE);
        var limiter = newLimiter();
        // Seed one below the limit — next check should still pass
        limiter.seedDailyCountForTest("u1", QUOTAS.getFree() - 1);
        assertThatCode(() -> limiter.check("u1")).doesNotThrowAnyException();
    }

    @Test
    void freeUser_oneOverDailyLimit_throws() {
        when(premiumService.resolveTier("u1")).thenReturn(SubscriptionTier.FREE);
        var limiter = newLimiter();
        limiter.seedDailyCountForTest("u1", QUOTAS.getFree());
        assertThatThrownBy(() -> limiter.check("u1"))
                .isInstanceOf(UpgradeRequiredException.class);
    }

    @Test
    void proUser_exactlyAtProLimit_doesNotThrow() {
        when(premiumService.resolveTier("u_pro")).thenReturn(SubscriptionTier.PRO);
        var limiter = newLimiter();
        limiter.seedDailyCountForTest("u_pro", QUOTAS.getPro() - 1);
        assertThatCode(() -> limiter.check("u_pro")).doesNotThrowAnyException();
    }

    @Test
    void proUser_isNeverDailyBlocked_becauseProIsUnlimited() {
        // REWRITTEN, NOT DELETED. This asserted PRO throws one over a 100/day cap.
        // PRO is unlimited at launch, so the contract inverted: seeding a huge count
        // must still not block. Implemented like MAX/FAMILY — the counter is skipped
        // entirely rather than compared against a very large number.
        when(premiumService.resolveTier("u_pro")).thenReturn(SubscriptionTier.PRO);
        ChatRateLimiter limiter = newLimiter();
        limiter.seedDailyCountForTest("u_pro", 100_000);

        limiter.check("u_pro"); // must not throw
    }

    @Test
    void burst_at29_doesNotThrow() {
        when(premiumService.resolveTier("u1")).thenReturn(SubscriptionTier.MAX);
        var limiter = newLimiter();
        for (int i = 0; i < 29; i++) {
            limiter.check("u1");
        }
        // 29th call should be fine; 30th is the limit
        assertThatCode(() -> limiter.check("u1")).doesNotThrowAnyException();
    }

    @Test
    void burst_at31_throws() {
        when(premiumService.resolveTier("u1")).thenReturn(SubscriptionTier.MAX);
        var limiter = newLimiter();
        for (int i = 0; i < 30; i++) {
            limiter.check("u1");
        }
        assertThatThrownBy(() -> limiter.check("u1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Slow down");
    }

    @Test
    void separateUsers_haveIndependentCounters() {
        when(premiumService.resolveTier("u1")).thenReturn(SubscriptionTier.FREE);
        when(premiumService.resolveTier("u2")).thenReturn(SubscriptionTier.FREE);
        var limiter = newLimiter();

        limiter.check("u1");
        limiter.check("u1");
        limiter.check("u2");

        assertThat(limiter.dailyHitsToday("u1")).isEqualTo(2);
        assertThat(limiter.dailyHitsToday("u2")).isEqualTo(1);
    }
}
