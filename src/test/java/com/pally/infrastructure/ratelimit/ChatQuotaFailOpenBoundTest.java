package com.pally.infrastructure.ratelimit;

import com.pally.domain.subscription.ChatQuotaProperties;
import com.pally.domain.subscription.PremiumService;
import com.pally.domain.subscription.SubscriptionTier;
import com.pally.shared.exception.UpgradeRequiredException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Pins the BOUNDED fail-open on the chat quota.
 *
 * <p>THE DEFECT: {@code check()} previously did
 * {@code catch (Exception ignored) { return; }} on a {@code resolveTier} failure,
 * with the comment "Never deny chat because a premium check blipped". That
 * {@code return} skipped the counter ENTIRELY — so a blip did not grant PRO, it
 * granted UNLIMITED, bypassing even the FREE cap. The cheapest way to get
 * unlimited chat was to make the entitlement lookup fail.
 *
 * <p>THE CONTRACT NOW: a failure degrades to the FREE tier and still counts. A
 * transient blip costs the user nothing; a persistent outage cannot be farmed.
 */
@ExtendWith(MockitoExtension.class)
class ChatQuotaFailOpenBoundTest {

    @Mock PremiumService premiumService;

    private final ChatQuotaProperties quotas = new ChatQuotaProperties();

    private ChatRateLimiter newLimiter() {
        return new ChatRateLimiter(premiumService, quotas);
    }

    @Test
    void tierLookupFailure_appliesTheFreeCap_ratherThanBypassingIt() {
        // THE EXACT DEFECT. Seed the counter AT the free cap, then make the tier
        // lookup blow up. Before the fix this returned early and allowed the call;
        // now it must be treated as FREE and refused.
        when(premiumService.resolveTier("u_blip"))
                .thenThrow(new RuntimeException("entitlement backend down"));
        ChatRateLimiter limiter = newLimiter();
        limiter.seedDailyCountForTest("u_blip", quotas.getFree());

        assertThatThrownBy(() -> limiter.check("u_blip"))
                .as("a failed tier lookup must fall back to FREE, never to unlimited")
                .isInstanceOf(UpgradeRequiredException.class);
    }

    @Test
    void tierLookupFailure_stillIncrementsTheCounter() {
        // The second half of the defect: even when the call is ALLOWED, the failure
        // path must count it. A `return` that skips counting lets a user hold the
        // entitlement service down and chat forever without ever accruing usage.
        when(premiumService.resolveTier("u_count"))
                .thenThrow(new RuntimeException("entitlement backend down"));
        ChatRateLimiter limiter = newLimiter();

        limiter.check("u_count");
        limiter.check("u_count");
        limiter.check("u_count");

        assertThat(limiter.dailyHitsToday("u_count"))
                .as("a blip must not make usage invisible")
                .isEqualTo(3);
    }

    @Test
    void tierLookupFailure_belowTheFreeCap_stillAllows() {
        // Bounded fail-open, not fail-closed: a student mid-session must not be cut
        // off by a blip. Below the FREE cap the call still succeeds.
        when(premiumService.resolveTier("u_ok"))
                .thenThrow(new RuntimeException("entitlement backend down"));
        ChatRateLimiter limiter = newLimiter();

        limiter.check("u_ok"); // must not throw

        assertThat(limiter.dailyHitsToday("u_ok")).isEqualTo(1);
    }

    @Test
    void proUser_isUnlimited_andIsNotCounted() {
        // PRO skips the counter entirely (like MAX/FAMILY) rather than comparing
        // against a very large number — otherwise we allocate and increment
        // per-user counters forever for users who can never hit a cap.
        when(premiumService.resolveTier("u_pro")).thenReturn(SubscriptionTier.PRO);
        ChatRateLimiter limiter = newLimiter();

        limiter.check("u_pro");
        limiter.check("u_pro");

        assertThat(limiter.dailyHitsToday("u_pro"))
                .as("an unlimited tier must not accrue a daily counter")
                .isZero();
    }

    @Test
    void quotasAreConfigDriven_notInlinedConstants() {
        // The reason the caps moved off `public static final int`: a compiler
        // INLINES those into every calling class, so a Railway override would have
        // moved the limiter while /usage kept reporting the old number. Reading a
        // mutated properties object here proves the limiter consults it at runtime.
        ChatQuotaProperties tuned = new ChatQuotaProperties();
        tuned.setFree(2);
        when(premiumService.resolveTier("u_cfg")).thenReturn(SubscriptionTier.FREE);
        ChatRateLimiter limiter = new ChatRateLimiter(premiumService, tuned);

        limiter.check("u_cfg");
        limiter.check("u_cfg");

        assertThatThrownBy(() -> limiter.check("u_cfg"))
                .as("the limiter must honour the configured cap, not a compiled-in one")
                .isInstanceOf(UpgradeRequiredException.class);
    }
}
