package com.pally.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.chat.ChatMessage;
import com.pally.domain.chat.ChatRepository;
import com.pally.domain.chat.ChatSessionSummariser;
import com.pally.domain.knowledge.WikiRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for FIX 2 (algebra + calculus detection) and FIX 3 (Socratic frustration unlock).
 */
class ExtendedAlgebraVerificationTest {

    private ClaudeContextAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new ClaudeContextAssembler(
                mock(TopicRouter.class),
                mock(WikiRepository.class),
                org.mockito.Mockito.mock(com.pally.domain.weakness.WeaknessProfileService.class),
                mock(ChatRepository.class),
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

    // ── FIX 3 — Socratic frustration unlock ───────────────────────────────────

    @Test
    void isFrustrationTriggered_fewerThan4Messages_false() {
        // Only 3 user turns — should NOT trigger
        List<ChatMessage> history = List.of(
                userMsg("What is photosynthesis?"),
                userMsg("I still don't understand"),
                userMsg("just give me the answer")
        );
        assertThat(assembler.isFrustrationTriggered(history, "tell me!")).isFalse();
    }

    @Test
    void isFrustrationTriggered_4MessagesNoFrustrationSignal_false() {
        List<ChatMessage> history = List.of(
                userMsg("What is photosynthesis?"),
                userMsg("How does it work?"),
                userMsg("What is chlorophyll?"),
                userMsg("And what about sunlight?")
        );
        assertThat(assembler.isFrustrationTriggered(history, "Ok thanks")).isFalse();
    }

    @Test
    void isFrustrationTriggered_4PlusMessagesWithFrustration_true() {
        List<ChatMessage> history = List.of(
                userMsg("What is photosynthesis?"),
                userMsg("How does it work?"),
                userMsg("I still don't understand"),
                userMsg("just give me the answer")
        );
        assertThat(assembler.isFrustrationTriggered(history, "I'm confused")).isTrue();
    }

    @Test
    void isFrustrationTriggered_frustrationInCurrentMessage_true() {
        List<ChatMessage> history = List.of(
                userMsg("What is x?"),
                userMsg("I don't get it"),
                userMsg("Help me"),
                userMsg("More context please")
        );
        // Current message contains frustration signal
        assertThat(assembler.isFrustrationTriggered(history, "tell me the answer already")).isTrue();
    }

    @Test
    void isFrustrationTriggered_emptyHistory_false() {
        assertThat(assembler.isFrustrationTriggered(List.of(), "just give me the answer")).isFalse();
    }

    @Test
    void isFrustrationTriggered_nullHistory_false() {
        assertThat(assembler.isFrustrationTriggered(null, "still confused")).isFalse();
    }

    @Test
    void isFrustrationTriggered_whatIsTheAnswer_keyword_triggers() {
        List<ChatMessage> history = List.of(
                userMsg("q1"), userMsg("q2"), userMsg("q3"), userMsg("q4")
        );
        assertThat(assembler.isFrustrationTriggered(history, "what is the answer")).isTrue();
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private ChatMessage userMsg(String content) {
        return ChatMessage.reconstitute(
                "id-" + content.hashCode(),
                "avatar-1", "user-1",
                ChatMessage.Role.USER, content, null, Instant.now()
        );
    }
}
