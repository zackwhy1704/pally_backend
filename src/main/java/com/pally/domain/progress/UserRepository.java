package com.pally.domain.progress;

import java.util.Optional;

public interface UserRepository {
    Optional<UserStats> findById(String userId);
    UserStats save(UserStats stats);
    void ensureUserExists(String userId);

    /**
     * Atomically adds {@code xp} and {@code stars} to the user's totals and
     * recomputes their level. Returns an {@link XpResult} so callers can
     * detect (and surface) a level-up crossing in the same round-trip —
     * single source of truth for the leveling signal.
     */
    XpResult addXpAndStars(String userId, int xp, int stars);

    /**
     * @param newXp     the user's XP total after the credit
     * @param oldLevel  level before the credit (1..MAX_LEVEL)
     * @param newLevel  level after the credit (1..MAX_LEVEL)
     * @param levelledUp {@code newLevel > oldLevel}
     */
    record XpResult(int newXp, int oldLevel, int newLevel, boolean levelledUp) {
        public static XpResult unchanged(int xp, int level) {
            return new XpResult(xp, level, level, false);
        }
    }
}
