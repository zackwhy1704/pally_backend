package com.pally.domain.progress;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Static catalog of rewards granted when a user crosses each level.
 * Functional rewards (e.g. +1 streak freeze) are applied in
 * UserRepositoryAdapter on the level-up crossing; cosmetic rewards exist
 * here purely so the UI can name a "next unlock" target.
 *
 * <p>Sequence is tuned to give an early visible win (L2/L3 cosmetic,
 * L5 functional freeze) before stretching to the long-tail rewards.
 */
public final class LevelRewards {
    private LevelRewards() {}

    public record Reward(int level, String label, Kind kind) {
        public enum Kind { COSMETIC, FUNCTIONAL, BADGE, MYSTERY }
    }

    /// Ordered by level so {@link #nextUnlock(int)} can scan in sequence.
    private static final Map<Integer, Reward> REWARDS;

    static {
        Map<Integer, Reward> m = new LinkedHashMap<>();
        m.put(2,  new Reward(2,  "Extra tutor slot",       Reward.Kind.COSMETIC));
        m.put(3,  new Reward(3,  "New Mochi colour",       Reward.Kind.COSMETIC));
        m.put(5,  new Reward(5,  "+1 streak freeze",       Reward.Kind.FUNCTIONAL));
        m.put(8,  new Reward(8,  "Sparkle avatar effect",  Reward.Kind.COSMETIC));
        m.put(10, new Reward(10, "Mystery box + Level 10 badge", Reward.Kind.MYSTERY));
        m.put(15, new Reward(15, "Golden name plate",      Reward.Kind.COSMETIC));
        m.put(20, new Reward(20, "Hall of Fame badge",     Reward.Kind.BADGE));
        m.put(25, new Reward(25, "Legendary tutor frame",  Reward.Kind.COSMETIC));
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
