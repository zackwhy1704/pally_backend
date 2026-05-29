package com.pally.domain.progress;

import org.junit.jupiter.api.Test;

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
        assertThat(r.label()).contains("tutor slot");
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
}
