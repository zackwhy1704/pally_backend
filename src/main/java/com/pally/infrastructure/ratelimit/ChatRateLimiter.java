package com.pally.infrastructure.ratelimit;

import com.pally.domain.subscription.ChatQuotaProperties;
import com.pally.domain.subscription.PremiumService;
import com.pally.domain.subscription.SubscriptionTier;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.exception.UpgradeRequiredException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
public class ChatRateLimiter {

    private static final int PER_USER_LIMIT = 30;
    private static final long WINDOW_MS = 60_000;

    /// Daily caps moved to ChatQuotaProperties (chat.quota.free / chat.quota.pro)
    /// so they are tunable from a Railway variable without an app update. They were
    /// `public static final int`, which a compiler INLINES into every calling class —
    /// so a config override would have moved the limiter while /usage kept reporting
    /// the old number, and the two surfaces would disagree about the same user.

    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();
    private final Map<String, DailyCount> dailyHits = new ConcurrentHashMap<>();

    private final PremiumService premiumService;
    private final ChatQuotaProperties quotas;

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
        // BOUNDED FAIL-OPEN. This used to `return` on any resolveTier failure,
        // which skipped the counter entirely — so a blip granted UNLIMITED chat,
        // bypassing even the FREE cap. That is the opposite of degrading safely:
        // the cheapest way to get unlimited access was to make the premium lookup
        // fail. A blip now degrades to the FREE tier and STILL increments the
        // counter, so a persistent outage cannot be farmed for free usage.
        SubscriptionTier tier;
        try {
            tier = premiumService.resolveTier(userId);
        } catch (Exception e) {
            // Logged, not swallowed: a silent fallback hides a broken entitlement
            // path, and this one was invisible for exactly that reason.
            log.warn("[ChatRateLimiter] tier lookup failed for user={} — applying FREE cap: {}",
                    userId, e.toString());
            tier = SubscriptionTier.FREE;
        }
        int dailyLimit = quotas.dailyLimitFor(tier);
        if (dailyLimit == ChatQuotaProperties.UNLIMITED) return; // no cap — skip counting

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
