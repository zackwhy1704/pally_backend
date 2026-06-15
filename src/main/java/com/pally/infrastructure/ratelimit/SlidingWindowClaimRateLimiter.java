package com.pally.infrastructure.ratelimit;

import com.pally.domain.account.ClaimRateLimiter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Adapts {@link SlidingWindowRateLimiter} to the domain {@link ClaimRateLimiter} port.
 * Constants mirror what was previously hard-coded in AccountController.
 */
@Component
@RequiredArgsConstructor
public class SlidingWindowClaimRateLimiter implements ClaimRateLimiter {

    private static final int CLAIM_FAIL_LIMIT = 5;
    private static final long CLAIM_WINDOW_MS = Duration.ofHours(1).toMillis();
    private static final String KEY_PREFIX = "claim:";

    private final SlidingWindowRateLimiter delegate;

    @Override
    public Result tryAcquire(String parentId) {
        SlidingWindowRateLimiter.Result r =
                delegate.tryAcquire(KEY_PREFIX + parentId, CLAIM_FAIL_LIMIT, CLAIM_WINDOW_MS);
        return new Result(r.allowed(), r.retryAfterSeconds());
    }

    @Override
    public void reset(String parentId) {
        delegate.reset(KEY_PREFIX + parentId);
    }
}
