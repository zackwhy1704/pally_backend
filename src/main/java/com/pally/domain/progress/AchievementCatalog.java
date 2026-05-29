package com.pally.domain.progress;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Static catalog mapping every badge id the system knows about to the
 * UI-facing metadata an achievements screen needs: human name,
 * description, category, rarity, and a numeric target (used by the
 * generic progress bar in the UI).
 *
 * <p>Keeping this catalog in code rather than the DB means a release
 * can add new achievements without a migration; the only DB state is
 * which badges a user has actually earned.
 */
public final class AchievementCatalog {
    private AchievementCatalog() {}

    public enum Category { STREAK, MASTERY, CURIOSITY, MILESTONE }

    public enum Rarity { COMMON, RARE, EPIC, LEGENDARY }

    public record Definition(
            String id,
            String name,
            String description,
            Category category,
            Rarity rarity,
            int target) {}

    private static final Map<String, Definition> DEFS;

    static {
        Map<String, Definition> m = new LinkedHashMap<>();
        // Streak family (rarity climbs with duration)
        m.put("STREAK_3", new Definition(
                "STREAK_3", "On a Roll", "3-day streak",
                Category.STREAK, Rarity.COMMON, 3));
        m.put("STREAK_7", new Definition(
                "STREAK_7", "Week Warrior", "7-day streak",
                Category.STREAK, Rarity.RARE, 7));
        m.put("STREAK_30", new Definition(
                "STREAK_30", "Month of Mastery", "30-day streak",
                Category.STREAK, Rarity.EPIC, 30));
        // Curiosity (first-action one-shots)
        m.put("FIRST_CHAT", new Definition(
                "FIRST_CHAT", "First Question", "Ask your tutor anything",
                Category.CURIOSITY, Rarity.COMMON, 1));
        m.put("FIRST_QUIZ", new Definition(
                "FIRST_QUIZ", "Pop Quiz", "Take your first quiz",
                Category.CURIOSITY, Rarity.COMMON, 1));
        m.put("FIRST_UPLOAD", new Definition(
                "FIRST_UPLOAD", "Notebook Open", "Upload your first study notes",
                Category.CURIOSITY, Rarity.COMMON, 1));
        m.put("PHOTOS_10", new Definition(
                "PHOTOS_10", "Snap Solver", "Solve 10 photo questions",
                Category.CURIOSITY, Rarity.RARE, 10));
        // Mastery (progress-based)
        m.put("QUIZ_CORRECT_50", new Definition(
                "QUIZ_CORRECT_50", "Quiz Whiz",
                "Get 50 quiz answers correct",
                Category.MASTERY, Rarity.RARE, 50));
        m.put("QUIZ_CORRECT_250", new Definition(
                "QUIZ_CORRECT_250", "Quiz Champion",
                "Get 250 quiz answers correct",
                Category.MASTERY, Rarity.EPIC, 250));
        m.put("PERFECT_QUIZ", new Definition(
                "PERFECT_QUIZ", "Flawless", "Get a perfect quiz score",
                Category.MASTERY, Rarity.RARE, 1));
        // Milestones (level)
        m.put("LEVEL_5", new Definition(
                "LEVEL_5", "Rising Star", "Reach Level 5",
                Category.MILESTONE, Rarity.COMMON, 5));
        m.put("LEVEL_10", new Definition(
                "LEVEL_10", "Shining Star", "Reach Level 10",
                Category.MILESTONE, Rarity.RARE, 10));
        DEFS = Map.copyOf(m);
    }

    public static List<Definition> all() {
        return List.copyOf(DEFS.values());
    }

    public static Definition byId(String id) {
        return DEFS.get(id);
    }
}
