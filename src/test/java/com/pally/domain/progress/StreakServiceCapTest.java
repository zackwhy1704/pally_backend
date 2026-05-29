package com.pally.domain.progress;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Locks the effective-freeze-cap rule so streak earn + shop purchase
/// stay aligned. Drift between the two is the original bug class this
/// helper exists to prevent.
class StreakServiceCapTest {

    @Test
    void cap_isThreeBelowL20() {
        for (int lvl = 1; lvl < 20; lvl++) {
            assertThat(StreakService.effectiveFreezeCap(lvl))
                    .as("level %d", lvl).isEqualTo(3);
        }
    }

    @Test
    void cap_isFiveAtOrAboveL20() {
        for (int lvl = 20; lvl <= 30; lvl++) {
            assertThat(StreakService.effectiveFreezeCap(lvl))
                    .as("level %d", lvl).isEqualTo(5);
        }
    }

    @Test
    void baseConstantUnchanged_forBackcompat() {
        // The legacy FREEZE_CAP constant remains for any caller that
        // pre-dates effectiveFreezeCap(). Make sure it still matches the
        // "below L20" branch.
        assertThat(StreakService.FREEZE_CAP).isEqualTo(3);
    }
}
