package com.pally.infrastructure.ratelimit;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generic in-memory sliding-window rate limiter used by both the
 * chat/photo (per-userId) and the auth login/forgot/PIN (per-IP+email)
 * paths. Same algorithm the prior ChatRateLimiter used; lifted here so
 * we don't fork the implementation.
 *
 * <p>Window is rolling: drop timestamps older than {@code windowMs},
 * count what remains. {@code limit} caps in-window throughput, not
 * lifetime usage.
 *
 * <p>TODO(scale): move to Redis before the 2nd replica — the scaling
 * plan tracks this; auth counters must move with the chat counters
 * (otherwise each replica gets its own count and the cap leaks N×).
 */
@Component
public class SlidingWindowRateLimiter {

    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public record Result(boolean allowed, long retryAfterSeconds) {
        public static Result ok() { return new Result(true, 0); }
        public static Result deny(long secs) { return new Result(false, secs); }
    }

    /// Returns the result without mutation — caller decides whether to
    /// throw, log, or branch. When allowed, the hit IS recorded (the
    /// idiom is "check-and-take" so concurrent callers can't both squeeze
    /// past the cap).
    public Result tryAcquire(String key, int limit, long windowMs) {
        if (key == null || key.isBlank()) return Result.ok();
        long now = Instant.now().toEpochMilli();
        long cutoff = now - windowMs;
        Deque<Long> deque = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (deque) {
            while (!deque.isEmpty() && deque.peekFirst() < cutoff) {
                deque.pollFirst();
            }
            if (deque.size() >= limit) {
                long retryAfterMs = (deque.peekFirst() + windowMs) - now;
                return Result.deny(Math.max(1, (retryAfterMs + 999) / 1000));
            }
            deque.addLast(now);
            return Result.ok();
        }
    }

    /// Clear a key — used by login on success so a kid who fat-fingered
    /// the password twice isn't locked out after their next valid login.
    public void reset(String key) {
        if (key == null) return;
        hits.remove(key);
    }
}
