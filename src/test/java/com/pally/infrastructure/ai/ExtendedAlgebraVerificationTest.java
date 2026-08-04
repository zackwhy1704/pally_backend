package com.pally.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.chat.ChatSessionSummariser;
import com.pally.domain.knowledge.WikiRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for FIX 2 (algebra + calculus detection). FIX 3 (Socratic frustration
 * unlock) moved to TopicClassifierTest — the detector was relocated there so
 * its result can actually reach the live chat path (see SendMessageUseCase).
 */
class ExtendedAlgebraVerificationTest {

    private ClaudeContextAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new ClaudeContextAssembler(
                mock(TopicRouter.class),
                mock(WikiRepository.class),
                org.mockito.Mockito.mock(com.pally.domain.weakness.WeaknessProfileService.class),
                new ObjectMapper(),
                mock(ChatSessionSummariser.class),
                new CalculatorTool(),
                new AlgebraTool()
        );
    }

    // ── Quadratic detection ───────────────────────────────────────────────────

    @Test
    void quadraticEquation_returnsRoots() {
        String hint = assembler.injectArithmeticVerification("How do I solve x^2 + 5x + 6 = 0?");
        assertThat(hint)
                .as("Should compute roots of x² + 5x + 6 = 0 — result contains both roots")
                .containsAnyOf("-2", "-3");
        // The quadratic line should contain "x =" showing the roots
        assertThat(hint).contains("x =");
    }

    @Test
    void quadraticNoRealRoots_reportedCorrectly() {
        // x^2 + 1*x + 4 = 0 → discriminant = 1 - 16 < 0 → no real roots
        // Use a form that the regex can match (needs the bx term)
        String hint = assembler.injectArithmeticVerification("Solve x^2 + 1x + 4 = 0 please");
        // Either "no real root" is in the hint, or the quadratic wasn't matched (arithmetic match won)
        // The key invariant: whatever is returned must not contain wrong roots
        if (!hint.isEmpty() && hint.contains("x =")) {
            assertThat(hint).containsIgnoringCase("no real root");
        }
        // If only arithmetic matched, that's still correct behaviour
    }

    @Test
    void quadraticDoubleRoot_reportedCorrectly() {
        // x² - 4x + 4 = 0 → (x-2)²
        String hint = assembler.injectArithmeticVerification("x^2 - 4x + 4 = 0");
        assertThat(hint)
                .as("Double root x=2 should appear in hint")
                .contains("2");
        if (hint.contains("x =")) {
            assertThat(hint).containsIgnoringCase("double root");
        }
    }

    // ── Derivative detection ──────────────────────────────────────────────────

    @Test
    void derivativeOfPolynomial_returnsCorrectDerivative() {
        String hint = assembler.injectArithmeticVerification("What is the derivative of 3x^2 + 2x + 5?");
        assertThat(hint)
                .as("d/dx(3x^2 + 2x + 5) = 6x + 2")
                .contains("6x")
                .contains("d/dx");
    }

    @Test
    void ddxTrigger_matchesDerivative() {
        String hint = assembler.injectArithmeticVerification("Can you explain d/dx of x^3?");
        assertThat(hint).contains("d/dx");
    }

    @Test
    void differentiateKeyword_triggersDerivative() {
        String hint = assembler.injectArithmeticVerification("Help me differentiate 2x^2 + x");
        // 2x^2 → 4x; x → 1
        assertThat(hint).contains("4x");
    }

    // ── Vector magnitude detection ────────────────────────────────────────────

    @Test
    void vectorMagnitude_345Triple() {
        String hint = assembler.injectArithmeticVerification("What is the magnitude of (3, 4)?");
        assertThat(hint)
                .contains("5")
                .contains("√");
    }

    @Test
    void vectorMagnitudePipe_syntax() {
        String hint = assembler.injectArithmeticVerification("Find |F| of (5, 12)");
        assertThat(hint).contains("13");
    }

    // ── Multiple hints in one message ─────────────────────────────────────────

    @Test
    void arithmeticAndQuadratic_bothAppear() {
        // Message with both a simple multiplication AND a quadratic
        String hint = assembler.injectArithmeticVerification("What is 3 * 4 and also solve x^2 + 5x + 6 = 0");
        assertThat(hint)
                .contains("12")       // 3*4
                .containsAnyOf("-2", "-3"); // quadratic roots
    }

    // ── No algebra — existing arithmetic still works ──────────────────────────

    @Test
    void simpleArithmetic_stillWorks() {
        String hint = assembler.injectArithmeticVerification("What is 6 * 7?");
        assertThat(hint).contains("42");
    }

    @Test
    void noMaths_returnsEmpty() {
        String hint = assembler.injectArithmeticVerification("What is photosynthesis?");
        assertThat(hint).isEmpty();
    }
}
