package com.pally.domain.module;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1 of the groundedness hardening: the gate runs ONLY on fact-claiming item
 * types (MICRO_CARD, PROVE_QUESTION). The invented-practice types are excluded
 * because grounding them against the source is a category error — a SPOT_MISTAKE
 * MUST contain a mistake, a HOT_TAKE a plausibly-false statement (this was 77% of
 * prod groundedness flags). Positive allow-list, so an unknown/future type is NOT
 * grounded-by-default — the fix-the-family discipline applied to gate coverage.
 */
class ModuleContentGeneratorGroundingScopeTest {

    @Test
    void factClaimingTypes_areGrounded() {
        assertThat(ModuleContentGenerator.isGroundedType("MICRO_CARD")).isTrue();
        assertThat(ModuleContentGenerator.isGroundedType("PROVE_QUESTION")).isTrue();
    }

    @Test
    void inventedPracticeTypes_areNOTGrounded() {
        assertThat(ModuleContentGenerator.isGroundedType("SPOT_MISTAKE")).isFalse();
        assertThat(ModuleContentGenerator.isGroundedType("HOT_TAKE")).isFalse();
        assertThat(ModuleContentGenerator.isGroundedType("CHALLENGE")).isFalse();
    }

    @Test
    void unknownOrFutureType_isNotGroundedByDefault() {
        assertThat(ModuleContentGenerator.isGroundedType("SOME_NEW_TYPE")).isFalse();
        assertThat(ModuleContentGenerator.isGroundedType(null)).isFalse();
    }
}
