package com.pally.shared.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DurationClampTest {

    @Test
    void nullDuration_mapsToZero_forBackwardCompatibility() {
        assertThat(DurationClamp.clamp(null)).isZero();
    }

    @Test
    void normalDuration_passesThroughUnchanged() {
        assertThat(DurationClamp.clamp(120)).isEqualTo(120);
    }

    @Test
    void negativeDuration_clampsToZero() {
        assertThat(DurationClamp.clamp(-5)).isZero();
    }

    @Test
    void overCeilingDuration_clampsTo3600() {
        assertThat(DurationClamp.clamp(99999)).isEqualTo(3600);
    }

    @Test
    void exactCeiling_passesThrough() {
        assertThat(DurationClamp.clamp(3600)).isEqualTo(3600);
    }
}
