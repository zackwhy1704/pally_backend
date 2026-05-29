package com.pally.infrastructure.ratelimit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SlidingWindowRateLimiterTest {

    private final SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter();

    @Test
    void tryAcquire_underLimit_allows() {
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("k", 5, 10_000).allowed()).isTrue();
        }
    }

    @Test
    void tryAcquire_pastLimit_denies_withRetryAfter() {
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire("k2", 5, 10_000);
        }
        var r = limiter.tryAcquire("k2", 5, 10_000);
        assertThat(r.allowed()).isFalse();
        assertThat(r.retryAfterSeconds()).isPositive();
    }

    @Test
    void reset_clearsCounter() {
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire("k3", 5, 10_000);
        }
        limiter.reset("k3");
        assertThat(limiter.tryAcquire("k3", 5, 10_000).allowed()).isTrue();
    }

    @Test
    void tryAcquire_blankKey_alwaysAllows() {
        for (int i = 0; i < 100; i++) {
            assertThat(limiter.tryAcquire("", 1, 10_000).allowed()).isTrue();
        }
    }
}
