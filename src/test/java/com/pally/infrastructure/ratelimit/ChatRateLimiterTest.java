package com.pally.infrastructure.ratelimit;

import com.pally.domain.subscription.PremiumService;
import com.pally.domain.subscription.SubscriptionTier;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.exception.UpgradeRequiredException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/// New behaviour: the limiter must expose today's counter so the chat UI
/// can show "N left" before the 402 wall. Locks both the inspection
/// method AND the existing daily-cap throw so a regression on either
/// surfaces here.
@ExtendWith(MockitoExtension.class)
class ChatRateLimiterTest {

    @Mock PremiumService premiumService;

    private ChatRateLimiter newLimiter() {
        return new ChatRateLimiter(premiumService);
    }

    @Test
    void dailyHits_startsAtZero() {
        var limiter = newLimiter();
        assertThat(limiter.dailyHitsToday("u1")).isZero();
    }

    @Test
    void dailyHits_incrementsOnCheck_forFreeUser() {
        when(premiumService.resolveTier("u1")).thenReturn(SubscriptionTier.FREE);
        var limiter = newLimiter();
        limiter.check("u1");
        assertThat(limiter.dailyHitsToday("u1")).isEqualTo(1);
        limiter.check("u1");
        assertThat(limiter.dailyHitsToday("u1")).isEqualTo(2);
    }

    @Test
    void dailyHits_throwsUpgradeRequired_pastFreeDailyCap() {
        when(premiumService.resolveTier("u1")).thenReturn(SubscriptionTier.FREE);
        var limiter = newLimiter();
        // The burst limit (30/min) fires before the daily limit when calling
        // check() rapidly, so we seed the daily counter just below the cap,
        // then call check() once more to confirm the daily gate fires.
        limiter.seedDailyCountForTest("u1", ChatRateLimiter.FREE_DAILY_LIMIT);
        assertThatThrownBy(() -> limiter.check("u1"))
                .isInstanceOf(UpgradeRequiredException.class)
                .satisfies(e ->
                        assertThat(((UpgradeRequiredException) e).getFeature())
                                .isEqualTo("CHAT_DAILY"));
    }

    @Test
    void proUser_hasHigherDailyCap_thenBlocks() {
        when(premiumService.resolveTier("u_pro")).thenReturn(SubscriptionTier.PRO);
        var limiter = newLimiter();
        // Seed at PRO limit — next call should throw
        limiter.seedDailyCountForTest("u_pro", ChatRateLimiter.PRO_DAILY_LIMIT);
        assertThatThrownBy(() -> limiter.check("u_pro"))
                .isInstanceOf(UpgradeRequiredException.class)
                .satisfies(e ->
                        assertThat(((UpgradeRequiredException) e).getFeature())
                                .isEqualTo("CHAT_DAILY"));
    }

    @Test
    void maxUser_bypassesDailyLimit_entirely() {
        when(premiumService.resolveTier("u_max")).thenReturn(SubscriptionTier.MAX);
        var limiter = newLimiter();
        // Seed daily counter far above free limit — MAX tier ignores it
        limiter.seedDailyCountForTest("u_max", 999);
        limiter.check("u_max"); // must not throw
    }

    @Test
    void familyUser_bypassesDailyLimit_entirely() {
        when(premiumService.resolveTier("u_fam")).thenReturn(SubscriptionTier.FAMILY);
        var limiter = newLimiter();
        limiter.seedDailyCountForTest("u_fam", 999);
        limiter.check("u_fam"); // must not throw
    }

    /// Burst limiter (30/min) throws plain BusinessException, NOT
    /// UpgradeRequired — the audit distinguishes "slow down" from
    /// "out for today." Verifying the type discriminator too because
    /// UpgradeRequiredException extends PallyException → asserting only
    /// the parent would let a regression slip past.
    @Test
    void burst_throwsBusinessException_notUpgradeRequired() {
        // MAX tier: bypass daily cap so only the burst limit fires.
        when(premiumService.resolveTier("u1")).thenReturn(SubscriptionTier.MAX);
        var limiter = newLimiter();
        for (int i = 0; i < 30; i++) {
            limiter.check("u1");
        }
        assertThatThrownBy(() -> limiter.check("u1"))
                .isInstanceOf(BusinessException.class)
                .isNotInstanceOf(UpgradeRequiredException.class)
                .hasMessageContaining("Slow down");
    }

    @Test
    void blankUserId_isNoop() {
        var limiter = newLimiter();
        limiter.check("");
        limiter.check(null);
        assertThat(limiter.dailyHitsToday("")).isZero();
        assertThat(limiter.dailyHitsToday(null)).isZero();
    }
}
