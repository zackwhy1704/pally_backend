package com.pally.domain.syllabus;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VerbatimSimilarityCheckerTest {

    private static final String SOURCE =
            "Binary is a base two number system that computers use internally to represent "
            + "all data because a transistor can only reliably hold one of two electrical states.";

    @Test
    void flagsExactCopy_asSuspiciouslyVerbatim() {
        // The failure this pins: an unmodified generator could paste source text straight
        // into a MICRO_CARD body. Without this checker, that reproduction is undetected.
        boolean suspicious = VerbatimSimilarityChecker.isSuspiciouslyVerbatim(SOURCE, SOURCE, 0.5);

        assertThat(suspicious).isTrue();
        assertThat(VerbatimSimilarityChecker.similarityRatio(SOURCE, SOURCE)).isEqualTo(1.0);
    }

    @Test
    void flagsLightlyReworded_nearVerbatimCopy() {
        // One word changed ("represent" -> "store") out of ~28 words still measures ~0.43
        // shingle overlap with the source — a lower bar than the exact-copy case (1.0),
        // but still far above genuinely original writing (0.0, see below), so 0.4 is the
        // right flag threshold for "lightly reworded", not "identical".
        String nearVerbatim =
                "Binary is a base two number system that computers use internally to store "
                + "all data because a transistor can only reliably hold one of two electrical states.";

        boolean suspicious = VerbatimSimilarityChecker.isSuspiciouslyVerbatim(nearVerbatim, SOURCE, 0.4);

        assertThat(suspicious).isTrue();
    }

    @Test
    void doesNotFlag_genuinelyOriginalExplanation() {
        String original =
                "Computers store everything as 1s and 0s because switches inside them can only be "
                + "on or off — that on/off pattern is what binary numbers represent.";

        boolean suspicious = VerbatimSimilarityChecker.isSuspiciouslyVerbatim(original, SOURCE, 0.5);

        assertThat(suspicious).isFalse();
    }

    @Test
    void similarityRatio_isZero_forCompletelyUnrelatedText() {
        double ratio = VerbatimSimilarityChecker.similarityRatio(
                "Photosynthesis converts sunlight into chemical energy in plants.", SOURCE);

        assertThat(ratio).isZero();
    }
}
