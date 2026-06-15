package com.pally.domain.account;

/**
 * Domain port for rate-limiting link-code claim attempts per parent.
 * Prevents brute-forcing the 32^6 code space.
 */
public interface ClaimRateLimiter {

    record Result(boolean allowed, long retryAfterSeconds) {}

    /**
     * Attempts to acquire a slot for {@code parentId} within the configured
     * window. Returns allowed=true when under the limit (and records the hit);
     * returns allowed=false with retryAfterSeconds when the limit is exceeded.
     */
    Result tryAcquire(String parentId);

    /**
     * Clears the hit record for {@code parentId} — called on successful claim
     * so a parent who fat-fingered a code earlier isn't penalised.
     */
    void reset(String parentId);
}
