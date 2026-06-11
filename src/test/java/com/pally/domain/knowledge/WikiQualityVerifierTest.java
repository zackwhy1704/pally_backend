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
}
