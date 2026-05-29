package com.pally.domain.progress;

/**
 * Aggregated learner-progress snapshot returned by the dashboard endpoint.
 *
 * <p>Leveling curve: quadratic, smooth, 30 levels total. The first few
 * thresholds are intentionally close together (dopamine for new users); the
 * gap widens linearly so a daily-active child takes a school year to cap.
 *
 * <p>{@code xpForLevel(L) = 50 * (L - 1) * L} →
 * 0, 100, 300, 600, 1000, 1500, … 43,500 (L = 30).
 */
public record ProgressSummary(
        String userId,
        int xp,
        int level,
        int xpToNextLevel,
        int streakDays,
        int stars,
        int totalFlashcards,
        int dueFlashcards,
        int totalQuizzesTaken,
        int averageScore,
        java.util.List<Integer> weekMinutes,
        java.util.List<String> badges
) {
    public static final int MAX_LEVEL = 30;
    private static final int CURVE = 50; // xpForLevel(L) = 50 * (L - 1) * L

    /** Cumulative XP needed to REACH level {@code level} (level &gt;= 1). */
    public static int xpForLevel(int level) {
        if (level <= 1) return 0;
        int capped = Math.min(level, MAX_LEVEL);
        return CURVE * (capped - 1) * capped;
    }

    /** Current level for a given lifetime XP total. Bounded to MAX_LEVEL. */
    public static int computeLevel(int xp) {
        for (int l = MAX_LEVEL; l >= 1; l--) {
            if (xp >= xpForLevel(l)) return l;
        }
        return 1;
    }

    /** XP remaining until the next level. 0 at max level. */
    public static int xpToNext(int xp) {
        int level = computeLevel(xp);
        if (level >= MAX_LEVEL) return 0;
        return xpForLevel(level + 1) - xp;
    }

    /** XP earned INSIDE the current level — the numerator for the bar. */
    public static int xpIntoLevel(int xp) {
        return xp - xpForLevel(computeLevel(xp));
    }

    /** Total XP span of the current level — the denominator for the bar. */
    public static int xpSpanForLevel(int xp) {
        int level = computeLevel(xp);
        if (level >= MAX_LEVEL) return 1; // avoid /0; UI renders full at max
        return xpForLevel(level + 1) - xpForLevel(level);
    }
}
