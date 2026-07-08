package com.pally.domain.module;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * masteryPct is a 0–100 contract. The serialization clamp is the last line of defence
 * so no legacy/miswritten row can ever render >100% client-side (the 2600% bug class).
 */
class MasteryScaleTest {

    @Test
    void clampPct_passesValidThrough_clampsOutOfRange_nullToZero() {
        assertThat(ModuleProgressionService.clampPct(new BigDecimal("26.00")))
                .isEqualByComparingTo("26.00");       // valid → unchanged
        assertThat(ModuleProgressionService.clampPct(new BigDecimal("150")))
                .isEqualByComparingTo("100");          // >100 → 100
        assertThat(ModuleProgressionService.clampPct(new BigDecimal("-5")))
                .isEqualByComparingTo("0");            // <0 → 0
        assertThat(ModuleProgressionService.clampPct(null))
                .isEqualByComparingTo("0");            // null → 0
    }
}
