package com.pally.domain.cost;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiCostRatesTest {

    private final AiCostRates rates = new AiCostRates();

    @Test
    void versionedClaudeModel_resolvesToFamilyRate_byPrefix() {
        // The compile/chat path passes claude-haiku-4-5-20251001; it must match
        // the "claude-haiku" rate, not fall through to zero.
        assertThat(rates.rateFor("claude-haiku-4-5-20251001")).isNotNull();
        assertThat(rates.estCostMicros("claude-haiku-4-5-20251001", 1_000_000, 0))
                .isEqualTo(800_000); // $0.80/M input
    }

    @Test
    void unknownModel_hasNoRate_andCostsZero() {
        assertThat(rates.rateFor("mistral-7b")).isNull();
        assertThat(rates.estCostMicros("mistral-7b", 1000, 1000)).isZero();
    }
}
