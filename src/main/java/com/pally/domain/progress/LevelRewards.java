package com.pally.domain.progress;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Static catalog of rewards granted when a user crosses each level.
 *
 * <p>FUNCTIONAL rewards are <b>implicit</b> — they are read at use time
 * (e.g. {@link StreakService#effectiveFreezeCap(int)} reads the user's
 * level, {@code CreateAvatarUseCase} consults {@link #freeTutorCap(int)}).
 * Nothing is written to the DB on crossing a level; the unlock label is
 * surfaced purely so the level-up overlay can name the reward.
 *
 * <p>The split: L1-5 = habit (cosmetic), L6-15 = tools (functional + small
 * QoL), L16-30 = privilege (persistent advantages). The aim is that a free
 * L20 kid feels rewarded for loyalty without breaking the premium boundary.
 */
public final class LevelRewards {
    private LevelRewards() {}

    public record Reward(int level, String label, Kind kind) {
        public enum Kind { COSMETIC, FUNCTIONAL, BADGE, MYSTERY }
    }

    /// Free users start with one tutor; L5 unlocks a second. Premium
    /// remains uncapped. Centralising the rule here keeps the source of
    /// truth in one place so callers don't recompute the +1 in every site.
    public static int freeTutorCap(int level) {
        return level >= 5 ? 2 : 1;
    }

    private static final Map<Integer, Reward> REWARDS;

    static {
        Map<Integer, Reward> m = new LinkedHashMap<>();
        m.put(2,  new Reward(2,  "New Mochi colour",            Reward.Kind.COSMETIC));
        m.put(3,  new Reward(3,  "Cloud background unlocked",   Reward.Kind.COSMETIC));
        m.put(5,  new Reward(5,  "Extra free tutor slot",       Reward.Kind.FUNCTIONAL));
        m.put(8,  new Reward(8,  "Sparkle avatar effect",       Reward.Kind.COSMETIC));
        m.put(10, new Reward(10, "Mystery box + Level 10 badge", Reward.Kind.MYSTERY));
        m.put(15, new Reward(15, "Golden name plate",           Reward.Kind.COSMETIC));
        m.put(20, new Reward(20, "Streak freeze cap raised to 5", Reward.Kind.FUNCTIONAL));
        m.put(25, new Reward(25, "Legendary tutor frame",       Reward.Kind.COSMETIC));
        m.put(30, new Reward(30, "Max level title — Pally Master", Reward.Kind.BADGE));
        REWARDS = Map.copyOf(m);
    }

    public static Reward atLevel(int level) {
        return REWARDS.get(level);
    }

    /// Next level (> currentLevel) that has a reward. Returns null when
    /// no further rewards remain (i.e. user is at or past the last entry).
    public static Reward nextUnlock(int currentLevel) {
        return REWARDS.values().stream()
                .filter(r -> r.level() > currentLevel)
                .min((a, b) -> Integer.compare(a.level(), b.level()))
                .orElse(null);
    }

    public static List<Reward> all() {
        return List.copyOf(REWARDS.values());
    }
}
