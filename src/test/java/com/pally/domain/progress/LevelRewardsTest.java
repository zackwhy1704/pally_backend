package com.pally.domain.progress;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Locks the new L5/L20 reward semantics. Regressions here change the
/// free-tier tutor cap or the streak-freeze ceiling silently — both have
/// big UX/retention consequences.
class LevelRewardsTest {

    @Test
    void freeTutorCap_isOne_belowL5() {
        for (int lvl = 1; lvl < 5; lvl++) {
            assertThat(LevelRewards.freeTutorCap(lvl))
                    .as("level %d", lvl).isEqualTo(1);
        }
    }

    @Test
    void freeTutorCap_isTwo_atOrAboveL5() {
        for (int lvl = 5; lvl <= 30; lvl++) {
            assertThat(LevelRewards.freeTutorCap(lvl))
                    .as("level %d", lvl).isEqualTo(2);
        }
    }

    @Test
    void atLevel_l5_isFunctionalTutorSlot() {
        var r = LevelRewards.atLevel(5);
        assertThat(r).isNotNull();
        assertThat(r.kind()).isEqualTo(LevelRewards.Reward.Kind.FUNCTIONAL);
        assertThat(r.label()).contains("Mochi slot");
    }

    @Test
    void atLevel_l20_isFunctionalFreezeCap() {
        var r = LevelRewards.atLevel(20);
        assertThat(r).isNotNull();
        assertThat(r.kind()).isEqualTo(LevelRewards.Reward.Kind.FUNCTIONAL);
        assertThat(r.label()).contains("freeze cap");
    }

    @Test
    void nextUnlock_returnsHigherLevel() {
        var r = LevelRewards.nextUnlock(1);
        assertThat(r).isNotNull();
        assertThat(r.level()).isEqualTo(2);
    }

    @Test
    void nextUnlock_pastEverything_returnsNull() {
        var r = LevelRewards.nextUnlock(99);
        assertThat(r).isNull();
    }

    @Test
    void starEarnMultiplier_isOne_belowL10() {
        for (int lvl = 1; lvl < 10; lvl++) {
            assertThat(LevelRewards.starEarnMultiplier(lvl))
                    .as("level %d", lvl).isEqualTo(1.0);
        }
    }

    @Test
    void starEarnMultiplier_isOnePointTwoFive_atOrAboveL10() {
        for (int lvl = 10; lvl <= 30; lvl++) {
            assertThat(LevelRewards.starEarnMultiplier(lvl))
                    .as("level %d", lvl).isEqualTo(1.25);
        }
    }

    @Test
    void l20FreezeStack_isFive() {
        // The size of the stack granted on first L20 crossing. Pinning
        // the constant so a refactor that drops or changes it would
        // surface here.
        assertThat(LevelRewards.L20_FREEZE_STACK).isEqualTo(5);
    }

    /// Snapshot of the exact en labels as they existed before the localization
    /// pass. Same shape as AchievementCatalogTest — a static-data snapshot,
    /// not a directive-append equality.
    private static final Map<Integer, String> EN_SNAPSHOT = Map.ofEntries(
            Map.entry(2, "New Mochi colour"),
            Map.entry(3, "Cloud background unlocked"),
            Map.entry(5, "Extra free Mochi slot"),
            Map.entry(8, "Sparkle avatar effect"),
            Map.entry(10, "Mystery box + Level 10 badge"),
            Map.entry(15, "Golden name plate"),
            Map.entry(20, "Streak freeze cap raised to 5"),
            Map.entry(25, "Legendary Mochi frame"),
            Map.entry(30, "Max level title — Apalchi Master")
    );

    @Test
    void en_isByteIdenticalToPreChangeSnapshot_forEveryReward() {
        assertThat(LevelRewards.all()).hasSize(EN_SNAPSHOT.size());
        for (var r : LevelRewards.all()) {
            assertThat(r.label()).as("level %d", r.level())
                    .isEqualTo(EN_SNAPSHOT.get(r.level()));
            assertThat(r.label("en")).isEqualTo(r.label());
            assertThat(r.label(null)).isEqualTo(r.label());
            assertThat(r.label("fr")).isEqualTo(r.label());
        }
    }

    @Test
    void zh_isNonBlankAndDiffersFromEn_forEveryReward() {
        for (var r : LevelRewards.all()) {
            assertThat(r.label("zh")).as("level %d zh", r.level())
                    .isNotBlank().isNotEqualTo(r.label());
        }
    }
}
