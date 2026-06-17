package com.pally.infrastructure.ratelimit;

import com.pally.domain.subscription.PremiumService;
import com.pally.domain.subscription.SubscriptionTier;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.exception.UpgradeRequiredException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import com.pally.shared.util.PallyTime;
import java.time.Instant;
import java.time.LocalDate;
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
@RequiredArgsConstructor
public class ChatRateLimiter {

    private static final int PER_USER_LIMIT = 30;
    private static final long WINDOW_MS = 60_000;
    /// Free-tier daily chat cap: 20 messages/day.
    /// Enough to experience the product meaningfully every day;
    /// creates a natural upgrade trigger once students want deeper sessions.
    /// Premium users are unlimited. Mochi count also gated via LevelRewards.freeTutorCap.
    public static final int FREE_DAILY_LIMIT = 20;

    /// Pro-tier daily chat cap: 100 messages/day.
    public static final int PRO_DAILY_LIMIT = 100;

    /**
     * Returns the daily chat limit for the given tier.
     * {@link Integer#MAX_VALUE} signals "unlimited" — callers should treat it as no cap.
     */
    public static int dailyLimitForTier(SubscriptionTier tier) {
        return switch (tier) {
            case FREE                    -> FREE_DAILY_LIMIT;
            case PRO                     -> PRO_DAILY_LIMIT;
            case MAX, FAMILY             -> Integer.MAX_VALUE;
        };
    }

    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();
    private final Map<String, DailyCount> dailyHits = new ConcurrentHashMap<>();

    private final PremiumService premiumService;

    private record DailyCount(LocalDate day, int count) {}

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

        // Daily cap is tier-based. Resolve tier once per call; MAX/FAMILY
        // skip the counter entirely (unlimited). FREE and PRO increment and
        // compare against their respective daily cap.
        SubscriptionTier tier;
        try {
            tier = premiumService.resolveTier(userId);
        } catch (Exception ignored) {
            // Never deny chat because a premium check blipped.
            return;
        }
        int dailyLimit = dailyLimitForTier(tier);
        if (dailyLimit == Integer.MAX_VALUE) return; // unlimited — skip counter

        LocalDate today = Instant.ofEpochMilli(now)
                .atZone(PallyTime.SGT).toLocalDate();
        DailyCount prev = dailyHits.get(userId);
        int nextCount = (prev != null && prev.day.equals(today))
                ? prev.count + 1
                : 1;
        if (nextCount > dailyLimit) {
            throw new UpgradeRequiredException("CHAT_DAILY");
        }
        dailyHits.put(userId, new DailyCount(today, nextCount));
    }

    /// Test-only: pre-seed the daily counter so tests can exercise the daily
    /// cap without having to call check() 80 times (which would trip the
    /// burst limiter at 30/min first).
    void seedDailyCountForTest(String userId, int count) {
        LocalDate today = Instant.now().atZone(PallyTime.SGT).toLocalDate();
        dailyHits.put(userId, new DailyCount(today, count));
    }

    /// Read-only inspection of today's used count for {@code userId}, used
    /// by the /usage/today endpoint to surface the remaining quota in the
    /// chat UI <i>before</i> the wall. Returns 0 for users that haven't
    /// chatted today or whose counter was evicted by a restart.
    public int dailyHitsToday(String userId) {
        if (userId == null || userId.isBlank()) return 0;
        LocalDate today = Instant.now()
                .atZone(PallyTime.SGT).toLocalDate();
        DailyCount d = dailyHits.get(userId);
        return (d != null && d.day.equals(today)) ? d.count : 0;
    }
}
