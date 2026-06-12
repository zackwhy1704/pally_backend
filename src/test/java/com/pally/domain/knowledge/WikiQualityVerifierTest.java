package com.pally.domain.knowledge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class WikiQualityVerifierTest {

    private WikiQualityVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new WikiQualityVerifier();
    }

    private WikiPage createPage(String slug, String title, String content) {
        return WikiPage.create("avatar-1", slug, title, content);
    }

    @Test
    void emptyContent_returnsZeroScore() {
        var page = createPage("test", "Test", "");
        var result = verifier.verify(page, "MATHS");
        assertThat(result.qualityScore()).isEqualTo(0.0);
        assertThat(result.issues()).hasSize(1);
        assertThat(result.issues().get(0)).containsIgnoringCase("no content");
    }

    @Test
    void shortContent_penalizesScore() {
        var page = createPage("test", "Test", "This is short.");
        var result = verifier.verify(page, "ENGLISH");
        assertThat(result.qualityScore()).isLessThan(1.0);
        assertThat(result.issues()).anyMatch(i -> i.contains("short"));
    }

    @Test
    void longContent_fullScore() {
        String content = "Photosynthesis is the process by which plants make food. ".repeat(10);
        var page = createPage("photo", "Photosynthesis", content);
        var result = verifier.verify(page, "SCIENCE");
        assertThat(result.qualityScore()).isEqualTo(1.0);
        assertThat(result.issues()).isEmpty();
    }

    @Test
    void mathContent_correctEquation_noIssues() {
        String content = "Addition: 2 + 3 = 5 is a basic operation. " + "word ".repeat(50);
        var page = createPage("add", "Addition", content);
        var result = verifier.verify(page, "MATHS");
        assertThat(result.issues()).noneMatch(i -> i.contains("math error"));
    }

    @Test
    void mathContent_wrongEquation_flagsIssue() {
        String content = "Addition: 2 + 3 = 7 is a basic operation. " + "word ".repeat(50);
        var page = createPage("add", "Addition", content);
        var result = verifier.verify(page, "MATHS");
        assertThat(result.issues()).anyMatch(i -> i.contains("math error"));
        assertThat(result.qualityScore()).isLessThan(1.0);
    }

    @Test
    void nonMathSubject_skipsEquationCheck() {
        String content = "Some text with 2 + 3 = 7 but subject is English. " + "word ".repeat(50);
        var page = createPage("eng", "English", content);
        var result = verifier.verify(page, "ENGLISH");
        // Should not flag math errors for non-STEM subjects
        assertThat(result.issues()).noneMatch(i -> i.contains("math error"));
    }

    @Test
    void verifySimpleEquations_multipleEquations_catchesBadOnes() {
        String text = "2 + 2 = 4 and 10 - 3 = 8 and 5 * 6 = 30";
        var errors = verifier.verifySimpleEquations(text);
        // 10 - 3 = 8 is wrong (should be 7)
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).contains("10");
    }

    @Test
    void verifySimpleEquations_multiplication_withX() {
        String text = "3 x 4 = 12";
        var errors = verifier.verifySimpleEquations(text);
        assertThat(errors).isEmpty();
    }

    @Test
    void verifySimpleEquations_wrongMultiplication() {
        String text = "3 x 4 = 15";
        var errors = verifier.verifySimpleEquations(text);
        assertThat(errors).hasSize(1);
    }

    // ── IMPROVEMENT 1: coherence (fragments) ─────────────────────────────────

    @Test
    void fragmentedContent_penalizesCoherence() {
        // 50+ words (so the length check passes) but split into tiny fragments,
        // most under 5 words → coherence penalty of 0.2.
        String content = ("Cells. Tiny. Very small. Big. Old. Plant cells here. "
                + "Animal cells too. Yes. No. Maybe. ").repeat(6);
        var page = createPage("cells", "Cells", content);
        var result = verifier.verify(page, "SCIENCE");
        assertThat(result.issues()).anyMatch(i -> i.contains("Low coherence"));
        assertThat(result.qualityScore()).isLessThan(1.0);
    }

    @Test
    void wellFormedSentences_noCoherencePenalty() {
        String content = ("The water cycle moves water around the planet every single day. "
                + "Water evaporates from the warm oceans and rises up into the cooler sky. ")
                .repeat(4);
        var page = createPage("water", "Water Cycle", content);
        var result = verifier.verify(page, "SCIENCE");
        assertThat(result.issues()).noneMatch(i -> i.contains("Low coherence"));
    }

    @Test
    void isMostlyFragments_singleSentence_notFlagged() {
        assertThat(verifier.isMostlyFragments("Short line.")).isFalse();
    }

    // ── IMPROVEMENT 1: subject-match density ─────────────────────────────────

    @Test
    void longOffTopicPage_penalizedForMissingSubjectTerms() {
        // 200+ words about cooking, none of the SCIENCE anchor terms present.
        String content = ("Today we baked a lovely cake with flour butter sugar and eggs. "
                + "We mixed everything in a big bowl and poured it into a round tin. ")
                .repeat(12);
        var page = createPage("cake", "Baking", content);
        var result = verifier.verify(page, "SCIENCE");
        assertThat(result.issues()).anyMatch(i -> i.contains("Off-topic"));
        assertThat(result.qualityScore()).isLessThan(1.0);
    }

    @Test
    void longOnTopicPage_noSubjectTermPenalty() {
        // Contains SCIENCE anchor "energy" / "cell" → no off-topic penalty.
        String content = ("Every living cell needs energy to survive and grow each day. "
                + "An organism gets energy from food through a chemical reaction inside. ")
                .repeat(10);
        var page = createPage("bio", "Biology", content);
        var result = verifier.verify(page, "SCIENCE");
        assertThat(result.issues()).noneMatch(i -> i.contains("Off-topic"));
    }

    @Test
    void shortOffTopicPage_notPenalizedForSubjectTerms() {
        // Below the 200-word density floor → density check does not apply.
        String content = "We baked a cake with flour and sugar today in the kitchen room. "
                + "word ".repeat(40);
        var page = createPage("cake", "Baking", content);
        var result = verifier.verify(page, "SCIENCE");
        assertThat(result.issues()).noneMatch(i -> i.contains("Off-topic"));
    }

    @Test
    void generalSubject_neverPenalizedForSubjectTerms() {
        String content = ("Anything at all can go here for a general tutor without a focus. ")
                .repeat(20);
        var page = createPage("misc", "Misc", content);
        var result = verifier.verify(page, "GENERAL");
        assertThat(result.issues()).noneMatch(i -> i.contains("Off-topic"));
    }

    // ── IMPROVEMENT 1: repetition ────────────────────────────────────────────

    @Test
    void highlyRepetitivePage_penalized() {
        // 200+ tokens dominated by one repeated word → repetition penalty.
        String content = "banana ".repeat(220);
        var page = createPage("rep", "Repeat", content);
        var result = verifier.verify(page, "ENGLISH");
        assertThat(result.issues()).anyMatch(i -> i.contains("Highly repetitive"));
        assertThat(result.qualityScore()).isLessThan(1.0);
    }

    @Test
    void variedLongPage_noRepetitionPenalty() {
        String content = ("The author uses a clever theme across the whole novel and its "
                + "characters grow through every difficult chapter they must face. ")
                .repeat(10);
        var page = createPage("lit", "Literature", content);
        var result = verifier.verify(page, "LITERATURE");
        assertThat(result.issues()).noneMatch(i -> i.contains("Highly repetitive"));
    }
}
