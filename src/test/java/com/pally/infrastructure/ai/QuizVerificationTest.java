package com.pally.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.quiz.QuizQuestion;
import com.pally.infrastructure.observability.ClaudeMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class QuizVerificationTest {

    private ClaudeQuizGenerator generator;
    private ClaudeMetrics metrics;

    @BeforeEach
    void setUp() {
        var registry = new SimpleMeterRegistry();
        metrics = new ClaudeMetrics(registry);
        var calc = new CalculatorTool();
        // ClaudeApiClient and ModelRouter not needed for verification tests
        generator = new ClaudeQuizGenerator(null, new ObjectMapper(), null, calc, metrics);
    }

    private QuizQuestion q(String question, List<String> options, int correctIndex) {
        return new QuizQuestion("id", "avatar", question, options, correctIndex, "slug", "");
    }

    // ── Correct answers pass through unchanged ────────────────────────────────

    @Test
    void correctAnswer_37times48_passesVerification() {
        var q = q("What is 37 × 48?",
                List.of("1686", "1776", "1776", "1786"), 1);
        var results = generator.verifyAndFilter(List.of(q), "a1");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).correctIndex()).isEqualTo(1);
    }

    @Test
    void nonNumericQuestion_passesWithoutVerification() {
        var q = q("Which gas is most abundant in Earth's atmosphere?",
                List.of("Oxygen", "Nitrogen", "Carbon dioxide", "Argon"), 1);
        var results = generator.verifyAndFilter(List.of(q), "a1");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).correctIndex()).isEqualTo(1);
    }

    // ── Wrong correctIndex gets corrected ─────────────────────────────────────

    @Test
    void wrongCorrectIndex_isAutoCorrected() {
        // 12 * 12 = 144; model said index 2 (169) but 144 is at index 0
        var q = q("What is 12 * 12?",
                List.of("144", "121", "169", "196"), 2);
        var results = generator.verifyAndFilter(List.of(q), "a1");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).correctIndex())
                .as("Should correct to index 0 (144)")
                .isEqualTo(0);
    }

    @Test
    void wrongCorrectIndex_347times89_corrected() {
        // 347 × 89 = 30883 — classic LLM arithmetic error
        var q = q("What is 347 × 89?",
                List.of("30,783", "30,883", "31,983", "29,883"), 0);
        var results = generator.verifyAndFilter(List.of(q), "a1");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).correctIndex())
                .as("30883 is at index 1 after comma stripping")
                .isEqualTo(1);
    }

    // ── Unrecoverable question is dropped ────────────────────────────────────

    @Test
    void unrecoverableQuestion_droppedWhenAnswerAbsent() {
        // 6 * 7 = 42; none of the options contain 42
        var q = q("What is 6 * 7?",
                List.of("36", "48", "56", "63"), 0);
        var results = generator.verifyAndFilter(List.of(q), "a1");
        assertThat(results)
                .as("Question should be dropped when correct answer is missing from options")
                .isEmpty();
    }

    // ── Mixed batch ───────────────────────────────────────────────────────────

    @Test
    void mixedBatch_correctsAndDropsAppropriately() {
        var good = q("What is 5 + 5?",
                List.of("10", "9", "11", "8"), 0); // correct
        var fixable = q("What is 4 * 4?",
                List.of("12", "16", "20", "24"), 2); // model says 20 but correct is 16 at index 1
        var bad = q("What is 3 * 3?",
                List.of("6", "12", "27", "81"), 2); // 9 not in options — drop

        var results = generator.verifyAndFilter(List.of(good, fixable, bad), "a1");

        assertThat(results).hasSize(2);
        assertThat(results.get(0).correctIndex()).isEqualTo(0);  // good unchanged
        assertThat(results.get(1).correctIndex()).isEqualTo(1);  // fixable corrected 2→1
        // bad was dropped
    }

    // ── Metrics fire on disagreement ─────────────────────────────────────────

    @Test
    void disagreementMetric_incrementedOnCorrection() {
        var registry = new SimpleMeterRegistry();
        var localMetrics = new ClaudeMetrics(registry);
        var localGenerator = new ClaudeQuizGenerator(null, new ObjectMapper(), null,
                new CalculatorTool(), localMetrics);

        var wrong = q("What is 2 + 2?",
                List.of("3", "4", "5", "6"), 0); // model says 3 but 4 is at index 1
        localGenerator.verifyAndFilter(List.of(wrong), "a1");

        assertThat(registry.get("pally.quiz.answer.disagreement").counter().count())
                .isGreaterThan(0);
    }
}
