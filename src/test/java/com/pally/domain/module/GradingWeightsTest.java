package com.pally.domain.module;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class GradingWeightsTest {

    @Test
    void deterministicIsFullTrust_selfReportIsQuarter_ungradedIsZero() {
        GradingWeights w = new GradingWeights();
        assertThat(w.weightFor(GradingSignal.DETERMINISTIC)).isEqualTo(1.0);
        assertThat(w.weightFor(GradingSignal.SELF_REPORT)).isEqualTo(0.30);
        assertThat(w.weightFor(GradingSignal.UNGRADED)).isEqualTo(0.0);
        // Deterministic is worth ~3.3x a self-report at the decided 0.30 weight.
        assertThat(w.weightFor(GradingSignal.DETERMINISTIC)
                / w.weightFor(GradingSignal.SELF_REPORT)).isCloseTo(3.333, within(0.01));
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
