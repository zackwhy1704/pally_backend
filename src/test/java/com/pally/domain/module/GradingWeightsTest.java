package com.pally.domain.module;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GradingWeightsTest {

    @Test
    void deterministicIsFullTrust_selfReportIsQuarter_ungradedIsZero() {
        GradingWeights w = new GradingWeights();
        assertThat(w.weightFor(GradingSignal.DETERMINISTIC)).isEqualTo(1.0);
        assertThat(w.weightFor(GradingSignal.SELF_REPORT)).isEqualTo(0.25);
        assertThat(w.weightFor(GradingSignal.UNGRADED)).isEqualTo(0.0);
        // The configured ratio the prompt requires: deterministic : self-report = 4 : 1.
        assertThat(w.weightFor(GradingSignal.DETERMINISTIC)
                / w.weightFor(GradingSignal.SELF_REPORT)).isEqualTo(4.0);
    }

    @Test
    void legacyNullSignal_keepsFullWeight_soExistingMasteryIsPreserved() {
        assertThat(new GradingWeights().weightFor(null)).isEqualTo(1.0);
    }

    @Test
    void selfReportWeightIsConfigDriven_notHardcoded() {
        GradingWeights w = new GradingWeights();
        w.setSelfReportWeight(0.5);
        assertThat(w.weightFor(GradingSignal.SELF_REPORT)).isEqualTo(0.5);
    }
}
