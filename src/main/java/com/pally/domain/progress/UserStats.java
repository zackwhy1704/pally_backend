package com.pally.domain.progress;

public record UserStats(
        String id,
        String displayName,
        int xp,
        int level,
        int streakDays,
        int stars
) {
    public UserStats withStars(int newStars) {
        return new UserStats(id, displayName, xp, level, streakDays, newStars);
    }

    public UserStats withXp(int newXp) {
        int newLevel = ProgressSummary.computeLevel(newXp);
        return new UserStats(id, displayName, newXp, newLevel, streakDays, stars);
    }
}
