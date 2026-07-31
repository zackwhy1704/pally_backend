package com.pally.domain.progress;

import com.pally.domain.i18n.SupportedLanguage;

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

    /**
     * {@code nameZh}/{@code descriptionZh} are pre-authored translations, not
     * derived from {@code name}/{@code description} — this is a static data
     * catalog (two independent fully-authored strings), not an LLM prompt
     * directive. {@link #name(String)}/{@link #description(String)} resolve
     * which one a caller sees; {@code name()}/{@code description()} stay the
     * English source of truth so existing callers are unaffected.
     */
    public record Definition(
            String id,
            String name,
            String description,
            String nameZh,
            String descriptionZh,
            Category category,
            Rarity rarity,
            int target) {
        public String name(String locale) {
            return SupportedLanguage.resolve(name, nameZh, locale);
        }

        public String description(String locale) {
            return SupportedLanguage.resolve(description, descriptionZh, locale);
        }
    }

    private static final Map<String, Definition> DEFS;

    static {
        Map<String, Definition> m = new LinkedHashMap<>();
        // Streak family (rarity climbs with duration)
        m.put("STREAK_3", new Definition(
                "STREAK_3", "On a Roll", "3-day streak",
                "势头正好", "连续学习3天",
                Category.STREAK, Rarity.COMMON, 3));
        m.put("STREAK_7", new Definition(
                "STREAK_7", "Week Warrior", "7-day streak",
                "一周勇士", "连续学习7天",
                Category.STREAK, Rarity.RARE, 7));
        m.put("STREAK_30", new Definition(
                "STREAK_30", "Month of Mastery", "30-day streak",
                "月度达人", "连续学习30天",
                Category.STREAK, Rarity.EPIC, 30));
        // Curiosity (first-action one-shots)
        m.put("FIRST_CHAT", new Definition(
                "FIRST_CHAT", "First Question", "Ask your tutor anything",
                "初次提问", "向你的导师提出任何问题",
                Category.CURIOSITY, Rarity.COMMON, 1));
        m.put("FIRST_QUIZ", new Definition(
                "FIRST_QUIZ", "Pop Quiz", "Take your first quiz",
                "小测验", "完成你的第一次测验",
                Category.CURIOSITY, Rarity.COMMON, 1));
        m.put("FIRST_UPLOAD", new Definition(
                "FIRST_UPLOAD", "Notebook Open", "Upload your first study notes",
                "打开笔记本", "上传你的第一份学习笔记",
                Category.CURIOSITY, Rarity.COMMON, 1));
        m.put("PHOTOS_10", new Definition(
                "PHOTOS_10", "Snap Solver", "Solve 10 photo questions",
                "拍照解题达人", "解答10道拍照题目",
                Category.CURIOSITY, Rarity.RARE, 10));
        // Mastery (progress-based)
        m.put("QUIZ_CORRECT_50", new Definition(
                "QUIZ_CORRECT_50", "Quiz Whiz",
                "Get 50 quiz answers correct",
                "测验高手", "答对50道测验题",
                Category.MASTERY, Rarity.RARE, 50));
        m.put("QUIZ_CORRECT_250", new Definition(
                "QUIZ_CORRECT_250", "Quiz Champion",
                "Get 250 quiz answers correct",
                "测验冠军", "答对250道测验题",
                Category.MASTERY, Rarity.EPIC, 250));
        m.put("PERFECT_QUIZ", new Definition(
                "PERFECT_QUIZ", "Flawless", "Get a perfect quiz score",
                "完美无瑕", "获得满分测验成绩",
                Category.MASTERY, Rarity.RARE, 1));
        // Milestones (level)
        m.put("LEVEL_5", new Definition(
                "LEVEL_5", "Rising Star", "Reach Level 5",
                "冉冉新星", "达到第5级",
                Category.MILESTONE, Rarity.COMMON, 5));
        m.put("LEVEL_10", new Definition(
                "LEVEL_10", "Shining Star", "Reach Level 10",
                "闪耀之星", "达到第10级",
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
