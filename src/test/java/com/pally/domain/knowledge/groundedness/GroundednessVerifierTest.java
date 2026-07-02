package com.pally.domain.knowledge.groundedness;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the fabrication-vs-elaboration decision table (the crux of B3) and the
 * cost contract (0 LLM calls on pre-pass hit; exactly 1 batched call otherwise).
 */
class GroundednessVerifierTest {

    private static final String SOURCE =
            "Photosynthesis converts light energy into chemical energy stored in glucose. "
            + "It happens in the chloroplasts of plant cells. "
            + "The French Revolution began in 1789.";

    private AtomicInteger judgeCalls;
    private GroundednessVerifier verifier;

    @BeforeEach
    void setUp() {
        judgeCalls = new AtomicInteger();
        // Content-based stub: contradicts a wrong date, marks a fabricated number /
        // unknown claim NOT_IN_SOURCE, supports anything else.
        EntailmentJudge stub = (source, claims) -> {
            judgeCalls.incrementAndGet();
            return claims.stream().map(c -> {
                String t = c.text().toLowerCase();
                if (t.contains("1798")) {
                    return new EntailmentJudge.Entailment(
                            EntailmentJudge.Verdict.CONTRADICTED, "The French Revolution began in 1789.");
                }
                if (t.contains("300000") || t.contains("producers")) {
                    return new EntailmentJudge.Entailment(EntailmentJudge.Verdict.NOT_IN_SOURCE, null);
                }
                return new EntailmentJudge.Entailment(EntailmentJudge.Verdict.SUPPORTED, "supported");
            }).toList();
        };
        verifier = new GroundednessVerifier(stub);
        ReflectionTestUtils.setField(verifier, "highOverlap", 0.6);
        ReflectionTestUtils.setField(verifier, "flagRateCeiling", 0.20);
    }

    @Test
    void case1_highOverlapRephrase_isSupportedByPrePass_zeroLlmCalls() {
        var report = verifier.check(SOURCE,
                List.of("Photosynthesis converts light energy into chemical energy."));
        assertThat(report.llmCalls()).isZero();
        assertThat(judgeCalls.get()).isZero();
        assertThat(report.needsAttention()).isFalse();
        assertThat(report.verdicts()).allMatch(v -> v.action() == GroundednessVerifier.Action.CLEAN);
    }

    @Test
    void sourceCoverage_absorbsClaimSpreadAcrossAdjacentSentences() {
        // False-positive fix: a claim grounded across TWO consecutive source
        // sentences must pre-pass. Single-sentence coverage is ~0.5 (< 0.6 gate);
        // the adjacent-pair window lifts it over the bar so it isn't false-flagged.
        String source = "Photosynthesis uses sunlight. Chlorophyll absorbs energy.";
        double cov = verifier.sourceCoverage(
                "photosynthesis uses sunlight and chlorophyll absorbs energy", source);
        assertThat(cov).isGreaterThanOrEqualTo(0.6);
    }

    @Test
    void case2_elaborationWithNoHardFact_isAllowed_notFlagged() {
        // Soft definitional claim, low overlap with the source → must be ALLOWED.
        var report = verifier.check(SOURCE, List.of("Plants are producers in the food chain."));
        assertThat(report.needsAttention()).isFalse();
        assertThat(report.verdicts()).anyMatch(v -> v.action() == GroundednessVerifier.Action.ALLOW);
        assertThat(report.flagged()).isEmpty();
    }

    @Test
    void case3_fabricatedNumberAbsentFromSource_isFlagged() {
        var report = verifier.check(SOURCE,
                List.of("The speed of light is 300000 kilometres per second."));
        assertThat(report.llmCalls()).isEqualTo(1);
        assertThat(report.flagged()).extracting(GroundednessVerifier.ClaimVerdict::claim)
                .anySatisfy(c -> assertThat(c).contains("300000"));
    }

    @Test
    void case4_contradictionOfSource_isContradicted_withSourceQuote() {
        var report = verifier.check(SOURCE, List.of("The French Revolution began in 1798."));
        assertThat(report.contradicted()).hasSize(1);
        assertThat(report.contradicted().get(0).sourceQuote()).contains("1789");
        assertThat(report.needsAttention()).isTrue();
    }

    @Test
    void case6_flagRateMetric_tripsWhenOverAggressive() {
        // 25 fabrication items (each asserts an invented "300000" the stub marks
        // NOT_IN_SOURCE) → flag rate ~100% over the sample, > ceiling.
        for (int i = 0; i < 25; i++) {
            verifier.check(SOURCE, List.of("The invented constant in item " + i + " is 300000 units."));
        }
        assertThat(verifier.currentFlagRate()).isGreaterThan(0.20);
    }

    @Test
    void mixedItem_makesExactlyOneBatchedCall_forAllLowOverlapClaims() {
        var report = verifier.check(SOURCE, List.of(
                "The speed of light is 300000 kilometres per second. " // fabricated number
                + "Plants are producers in the food chain."));          // soft elaboration rides along
        assertThat(report.llmCalls()).isEqualTo(1); // ONE call, not one-per-claim
        assertThat(judgeCalls.get()).isEqualTo(1);
        assertThat(report.flagged()).hasSize(1);    // only the hard fact flags
        assertThat(report.verdicts()).anyMatch(v -> v.action() == GroundednessVerifier.Action.ALLOW);
    }
}
