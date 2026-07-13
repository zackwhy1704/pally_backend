package com.pally.domain.knowledge.groundedness;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClaimExtractorTest {

    @Test
    void numberSentence_isExtractedAsHardFact() {
        List<Claim> claims = ClaimExtractor.extract("The speed of light is 300000 km per second.");
        assertThat(claims).hasSize(1);
        assertThat(claims.get(0).hardFact()).isTrue();
    }

    @Test
    void formula_isHardFact() {
        assertThat(ClaimExtractor.isHardFact("Energy equals mass times c, written E=mc^2.")).isTrue();
        assertThat(ClaimExtractor.isHardFact("A fraction like 3/4 shows three parts of four.")).isTrue();
    }

    @Test
    void softDefinition_isExtractedButNotHardFact() {
        // Definitional verb keeps it (it's a claim), but no number/formula/entity →
        // soft → must NOT be treated as a flaggable hard fact (elaboration).
        List<Claim> claims = ClaimExtractor.extract("Plants are producers in the food chain.");
        assertThat(claims).hasSize(1);
        assertThat(claims.get(0).hardFact()).isFalse();
    }

    @Test
    void scaffoldingAndQuestions_areExcluded() {
        List<Claim> claims = ClaimExtractor.extract(
                "Let's explore fractions together. What is a fraction? "
                + "Remember to practise every day.");
        // "Let's…" and "Remember…" are scaffolding; "What is a fraction?" is a question.
        assertThat(claims).isEmpty();
    }

    @Test
    void pureElaborationWithNoFactOrDefinition_isSkipped() {
        // No number/formula/entity and no definitional verb → presumed-fine, skipped.
        List<Claim> claims = ClaimExtractor.extract("This helps you understand the topic better.");
        assertThat(claims).isEmpty();
    }

    // ── #2: heading/title lines are NOT claims (the false-positive fix) ──────
    // NAMED_ENTITY ("The Never-Ending Journey") used to make a title a hard fact →
    // flagged as ungrounded, inflating the flag rate. These must now be dropped.

    @Test
    void titleCaseHeadings_areNotClaims() {
        for (String heading : List.of(
                "The Never-Ending Journey!",
                "The Important Rule About Units",
                "Great Job!",
                "Girls' Special Sales Power!",
                "Evaporation: Water Goes Up!")) {
            assertThat(ClaimExtractor.extract(heading))
                    .as("heading %s must yield no claim", heading)
                    .isEmpty();
            assertThat(ClaimExtractor.isHeadingOrTitle(heading)).isTrue();
        }
    }

    @Test
    void realFactWithoutTrailingPeriod_isStillKept() {
        // Precision guard: the heading filter must NOT drop a genuine hard fact just
        // because it lacks a period — only 1 capitalised word, so not a title.
        List<Claim> claims = ClaimExtractor.extract("Water boils at 100 degrees Celsius");
        assertThat(claims).hasSize(1);
        assertThat(claims.get(0).hardFact()).isTrue();
        assertThat(ClaimExtractor.isHeadingOrTitle("Water boils at 100 degrees Celsius")).isFalse();
    }

    @Test
    void titleLineIsDropped_butTheBodyFactBeneathItIsKept() {
        // A MICRO_CARD often ships a title line + a body sentence; only the fact stays.
        List<Claim> claims = ClaimExtractor.extract(
                "The Never-Ending Journey!\nThe water cycle repeats about 100 times.");
        assertThat(claims).hasSize(1);
        assertThat(claims.get(0).text()).contains("water cycle repeats");
    }
}
