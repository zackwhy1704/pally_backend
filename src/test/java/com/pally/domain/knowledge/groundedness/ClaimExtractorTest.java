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
}
