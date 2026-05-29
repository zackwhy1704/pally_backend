package com.pally.infrastructure.ratelimit;

import com.pally.domain.subscription.PremiumService;
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

    private PremiumService.Entitlement free() {
        return new PremiumService.Entitlement(false, "NONE", null, "free", null);
    }

    @Test
    void dailyHits_startsAtZero() {
        var limiter = newLimiter();
        assertThat(limiter.dailyHitsToday("u1")).isZero();
    }

    @Test
    void dailyHits_incrementsOnCheck() {
        when(premiumService.resolve("u1")).thenReturn(free());
        var limiter = newLimiter();
        limiter.check("u1");
        assertThat(limiter.dailyHitsToday("u1")).isEqualTo(1);
        limiter.check("u1");
        assertThat(limiter.dailyHitsToday("u1")).isEqualTo(2);
    }

    @Test
    void dailyHits_throwsUpgradeRequired_pastDailyCap() {
        when(premiumService.resolve("u1")).thenReturn(free());
        var limiter = newLimiter();
        for (int i = 0; i < ChatRateLimiter.FREE_DAILY_LIMIT; i++) {
            limiter.check("u1");
        }
        // The exception's feature() is the typed signal — the message itself
        // is a kid-friendly sentence, not the code.
        assertThatThrownBy(() -> limiter.check("u1"))
                .isInstanceOf(UpgradeRequiredException.class)
                .satisfies(e ->
                        assertThat(((UpgradeRequiredException) e).getFeature())
                                .isEqualTo("CHAT_DAILY"));
    }

    /// Premium bypass — daily-cap doesn't fire. Burst still does (real
    /// rule), so we keep the volume well under 30 to isolate the daily
    /// branch.
    @Test
    void premium_bypassesDailyLimit() {
        when(premiumService.resolve("u1"))
                .thenReturn(new PremiumService.Entitlement(
                        true, "SELF", "family_monthly", "active", null));
        var limiter = newLimiter();
        // Free cap is 20 → 25 would trip CHAT_DAILY for a free user.
        // Premium should sail through.
        for (int i = 0; i < 25; i++) {
            limiter.check("u1");
        }
        // No throw.
    }

    /// Burst limiter (30/min) throws plain BusinessException, NOT
    /// UpgradeRequired — the audit distinguishes "slow down" from
    /// "out for today." Verifying the type discriminator too because
    /// UpgradeRequiredException extends PallyException → asserting only
    /// the parent would let a regression slip past.
    @Test
    void burst_throwsBusinessException_notUpgradeRequired() {
        // Premium bypass leaves only the burst limit active.
        when(premiumService.resolve("u1"))
                .thenReturn(new PremiumService.Entitlement(
                        true, "SELF", "family_monthly", "active", null));
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
