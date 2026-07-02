package com.pally.domain.marking;

import com.pally.domain.marking.AgreementScorer.GradeAgreement;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** The agreement metric is hard-data: grade band diff + comment token similarity. */
class AgreementScorerTest {

    @Test
    void gradeAgreement_exactAcrossFormats() {
        assertThat(AgreementScorer.gradeAgreement("3/5", "3/5")).isEqualTo(GradeAgreement.EXACT);
        assertThat(AgreementScorer.gradeAgreement("60%", "3/5")).isEqualTo(GradeAgreement.EXACT); // both band 3
        assertThat(AgreementScorer.gradeAgreement("A", "A")).isEqualTo(GradeAgreement.EXACT);
    }

    @Test
    void gradeAgreement_withinOneBandVsMismatch() {
        assertThat(AgreementScorer.gradeAgreement("3/5", "4/5")).isEqualTo(GradeAgreement.WITHIN_ONE_BAND);
        assertThat(AgreementScorer.gradeAgreement("A", "C")).isEqualTo(GradeAgreement.MISMATCH);
        assertThat(AgreementScorer.gradeAgreement("1/5", "5/5")).isEqualTo(GradeAgreement.MISMATCH);
    }

    @Test
    void gradeAgreement_unknownWhenUnparseable() {
        assertThat(AgreementScorer.gradeAgreement("good effort", "3/5")).isEqualTo(GradeAgreement.UNKNOWN);
        assertThat(AgreementScorer.gradeAgreement(null, "3/5")).isEqualTo(GradeAgreement.UNKNOWN);
    }

    @Test
    void commentSimilarity_verbatimIsOne_rewriteIsLow() {
        assertThat(AgreementScorer.commentSimilarity("Good method, watch the units.",
                "Good method, watch the units.")).isEqualTo(1.0);
        double sim = AgreementScorer.commentSimilarity(
                "Correct formula used throughout.",
                "You lost a mark for missing units on the final answer.");
        assertThat(sim).isLessThan(0.4);
    }

    @Test
    void commentSimilarity_partialEditIsMiddling() {
        double sim = AgreementScorer.commentSimilarity(
                "Good method, watch the units.",
                "Good method, but watch the units next time.");
        assertThat(sim).isBetween(0.4, 0.95);
    }

    @Test
    void conceptsAddedByTeacher_detectsWhatAiMissed() {
        var added = AgreementScorer.conceptsAddedByTeacher(
                "Your working shows the correct method.",
                "Correct method, but you lost a mark on units and rounding.");
        assertThat(added).contains("units", "rounding").doesNotContain("method");
    }

    @Test
    void toBand_scalesPercentAndFraction() {
        assertThat(AgreementScorer.toBand("80%")).isCloseTo(4.0, within(0.01));
        assertThat(AgreementScorer.toBand("4/5")).isCloseTo(4.0, within(0.01));
    }
}
