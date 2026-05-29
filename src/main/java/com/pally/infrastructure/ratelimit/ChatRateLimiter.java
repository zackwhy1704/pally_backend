package com.pally.infrastructure.ratelimit;

import com.pally.shared.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-user sliding-window rate limiter for Claude-backed chat/photo calls.
 *
 * <p>Why: a kid mashing the send button (or a buggy client retry loop) can
 * burn through API budget in seconds. We deliberately keep this in-process
 * — no Redis dep — because Pally runs on a single Railway dyno and the
 * blast radius of a process restart resetting counters is acceptable
 * (worst case a user gets a few extra messages through).
 *
 * <p>Window is rolling: we drop timestamps older than {@code WINDOW_MS},
 * then count what remains. {@link #PER_USER_LIMIT} caps active throughput,
 * not lifetime usage.
 */
@Component
public class ChatRateLimiter {

    private static final int PER_USER_LIMIT = 30;
    private static final long WINDOW_MS = 60_000;

    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public void check(String userId) {
        if (userId == null || userId.isBlank()) return;
        long now = Instant.now().toEpochMilli();
        long cutoff = now - WINDOW_MS;
        Deque<Long> deque = hits.computeIfAbsent(userId, k -> new ArrayDeque<>());
        synchronized (deque) {
            while (!deque.isEmpty() && deque.peekFirst() < cutoff) {
                deque.pollFirst();
            }
            if (deque.size() >= PER_USER_LIMIT) {
                long retryAfterMs = (deque.peekFirst() + WINDOW_MS) - now;
                throw new BusinessException(
                        "Slow down a little — you've sent a lot of messages."
                                + " Try again in " + Math.max(1,
                                        (retryAfterMs + 999) / 1000)
                                + "s.",
                        429);
            }
            deque.addLast(now);
        }
    }
}
