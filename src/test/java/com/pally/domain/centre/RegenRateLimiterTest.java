package com.pally.domain.centre;

import com.pally.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegenRateLimiterTest {

    @Test
    void checkAndRecord_allowsUpToMaxPerDay() {
        RegenRateLimiter limiter = new RegenRateLimiter();

        for (int i = 0; i < RegenRateLimiter.MAX_PER_DAY; i++) {
            assertThatCode(() -> limiter.checkAndRecord("class-1", "slug-1"))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void checkAndRecord_blocksOnceCapReached() {
        RegenRateLimiter limiter = new RegenRateLimiter();

        for (int i = 0; i < RegenRateLimiter.MAX_PER_DAY; i++) {
            limiter.checkAndRecord("class-1", "slug-1");
        }

        assertThatThrownBy(() -> limiter.checkAndRecord("class-1", "slug-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Regeneration limit reached");
    }

    @Test
    void checkAndRecord_differentPagesAreIndependent() {
        RegenRateLimiter limiter = new RegenRateLimiter();

        for (int i = 0; i < RegenRateLimiter.MAX_PER_DAY; i++) {
            limiter.checkAndRecord("class-1", "slug-1");
        }

        // slug-2 has its own bucket — should not be blocked
        assertThatCode(() -> limiter.checkAndRecord("class-1", "slug-2"))
                .doesNotThrowAnyException();
    }

    @Test
    void checkAndRecord_differentClassesSameSlugAreIndependent() {
        RegenRateLimiter limiter = new RegenRateLimiter();

        for (int i = 0; i < RegenRateLimiter.MAX_PER_DAY; i++) {
            limiter.checkAndRecord("class-1", "slug-1");
        }

        assertThatCode(() -> limiter.checkAndRecord("class-2", "slug-1"))
                .doesNotThrowAnyException();
    }
}
