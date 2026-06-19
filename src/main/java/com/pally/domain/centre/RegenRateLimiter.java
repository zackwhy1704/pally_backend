package com.pally.domain.centre;

import com.pally.shared.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process rate limiter for centre content regeneration.
 * Caps regeneration at {@value #MAX_PER_DAY} calls per (classId, pageSlug) per 24 hours.
 * State is lost on restart — acceptable because the limit exists to protect AI margin
 * during normal operation, not as a security boundary.
 */
@Component
public class RegenRateLimiter {

    static final int MAX_PER_DAY = 3;

    private final ConcurrentHashMap<String, Deque<Instant>> log = new ConcurrentHashMap<>();

    /**
     * Records a regeneration attempt. Throws {@link BusinessException} (429) when
     * the caller has already hit the daily cap for this page.
     */
    public void checkAndRecord(String classId, String pageSlug) {
        String key = classId + ":" + pageSlug;
        Deque<Instant> timestamps = log.computeIfAbsent(key, k -> new ArrayDeque<>());

        synchronized (timestamps) {
            Instant cutoff = Instant.now().minus(24, ChronoUnit.HOURS);
            timestamps.removeIf(t -> t.isBefore(cutoff));

            if (timestamps.size() >= MAX_PER_DAY) {
                throw new BusinessException(
                        "Regeneration limit reached — this topic can be regenerated at most "
                        + MAX_PER_DAY + " times per day.", 429);
            }
            timestamps.addLast(Instant.now());
        }
    }
}
