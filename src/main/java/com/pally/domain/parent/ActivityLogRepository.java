package com.pally.domain.parent;

import java.time.Instant;

/**
 * Domain port for read-only activity-log queries used by the parent dashboard
 * and weekly-report views. Write access (logging activity) stays in
 * {@code ActivityLogService} which owns the write path.
 */
public interface ActivityLogRepository {

    /** Number of activity rows since {@code since}. */
    int countSince(String userId, Instant since);

    /** Total XP earned since {@code since}. */
    int sumXpSince(String userId, Instant since);

    /** Total minutes studied in [from, to). */
    int sumMinutesBetween(String userId, Instant from, Instant to);

    /** Number of activity rows in [from, to). */
    int countBetween(String userId, Instant from, Instant to);

    /** Total XP earned in [from, to). */
    int sumXpBetween(String userId, Instant from, Instant to);
}
