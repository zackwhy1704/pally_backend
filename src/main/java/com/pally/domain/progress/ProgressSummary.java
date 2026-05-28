package com.pally.domain.progress;

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
    public static int computeLevel(int xp) {
        int[] thresholds = {0, 100, 250, 500, 900, 1400, 2000, 2800, 3800, 5000};
        int level = 1;
        for (int i = 1; i < thresholds.length; i++) {
            if (xp >= thresholds[i]) level = i + 1;
            else break;
        }
        return level;
    }

    public static int xpToNext(int xp) {
        int[] thresholds = {0, 100, 250, 500, 900, 1400, 2000, 2800, 3800, 5000};
        for (int threshold : thresholds) {
            if (xp < threshold) return threshold - xp;
        }
        return 0;
    }
}
