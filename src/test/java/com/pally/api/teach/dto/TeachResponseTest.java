package com.pally.api.teach.dto;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// Locks withLevel's rewardLabel param (added alongside quiz/chat/photo
/// level-up-wiring) — a pure copy method, no mocking needed.
class TeachResponseTest {

    private TeachResponse base() {
        return new TeachResponse(8, 10, 15,
                List.of("concept1"), List.of(), null, "Nice work!");
    }

    @Test
    void backCompatCtor_defaultsLevelSignalsAndRewardLabelToNull() {
        var r = base();

        assertThat(r.levelledUp()).isFalse();
        assertThat(r.newLevel()).isEqualTo(0);
        assertThat(r.rewardLabel()).isNull();
        assertThat(r.status()).isEqualTo(TeachResponse.Status.OK);
    }

    @Test
    void withLevel_populatesLevelSignalsAndRewardLabel_preservesEverythingElse() {
        var r = base().withLevel(true, 5, "Extra free Mochi slot");

        assertThat(r.levelledUp()).isTrue();
        assertThat(r.newLevel()).isEqualTo(5);
        assertThat(r.rewardLabel()).isEqualTo("Extra free Mochi slot");
        // Everything else untouched.
        assertThat(r.score()).isEqualTo(8);
        assertThat(r.feedback()).isEqualTo("Nice work!");
        assertThat(r.status()).isEqualTo(TeachResponse.Status.OK);
    }

    @Test
    void withLevel_noReward_rewardLabelIsNull() {
        var r = base().withLevel(false, 0, null);

        assertThat(r.rewardLabel()).isNull();
    }

    @Test
    void evalFailed_defaultsRewardLabelToNull() {
        var r = TeachResponse.evalFailed("try again");

        assertThat(r.rewardLabel()).isNull();
        assertThat(r.status()).isEqualTo(TeachResponse.Status.EVAL_FAILED);
    }
}
