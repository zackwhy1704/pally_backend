package com.pally.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.chat.ChatSessionSummariser;
import com.pally.domain.knowledge.WikiRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for FIX 3 — arithmetic verification injected into Block 4 of the system prompt.
 */
class ArithmeticVerificationTest {

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

    // ── Happy path — expression found and evaluated ───────────────────────────

    @ParameterizedTest(name = "message=''{0}'' → hint contains ''{1}''")
    @CsvSource({
        "'What is 6 * 7?',      '6 * 7 = 42'",
        "'Can you solve 100/4', '100 / 4 = 25'",
        "'Help me with 15 + 27','15 + 27 = 42'",
        "'What is 8 - 3',       '8 - 3 = 5'",
    })
    void simpleExpression_returnsVerifiedHint(String message, String expected) {
        String hint = assembler.injectArithmeticVerification(message);
        assertThat(hint).contains(expected);
    }

    @Test
    void unicodeMultiply_evaluatedCorrectly() {
        String hint = assembler.injectArithmeticVerification("What is 6 × 7?");
        assertThat(hint).contains("= 42");
    }

    @Test
    void unicodeDivide_evaluatedCorrectly() {
        String hint = assembler.injectArithmeticVerification("Solve 56 ÷ 8 please");
        assertThat(hint).contains("= 7");
    }

    // ── No arithmetic — return empty ──────────────────────────────────────────

    @Test
    void noArithmetic_returnsEmpty() {
        String hint = assembler.injectArithmeticVerification("What is photosynthesis?");
        assertThat(hint).isEmpty();
    }

    @Test
    void nullMessage_returnsEmpty() {
        String hint = assembler.injectArithmeticVerification(null);
        assertThat(hint).isEmpty();
    }

    @Test
    void blankMessage_returnsEmpty() {
        String hint = assembler.injectArithmeticVerification("   ");
        assertThat(hint).isEmpty();
    }

    // ── Division by zero — handled gracefully (empty, no throw) ──────────────

    @Test
    void divisionByZero_returnsEmpty() {
        // CalculatorTool throws on /0 — should be caught and return ""
        String hint = assembler.injectArithmeticVerification("What is 5 / 0?");
        assertThat(hint).isEmpty();
    }

    // ── Hint format ───────────────────────────────────────────────────────────

    @Test
    void hint_showsOriginalOperatorSymbol() {
        // The hint should preserve the user's operator symbol, not the ASCII normalised one
        String hint = assembler.injectArithmeticVerification("6 × 7 is what?");
        assertThat(hint)
                .as("Hint should show the original × symbol in the display expression")
                .contains("×");
    }

    @Test
    void hint_showsOperands() {
        String hint = assembler.injectArithmeticVerification("What is 37 * 48?");
        assertThat(hint).contains("37");
        assertThat(hint).contains("48");
        assertThat(hint).contains("=");
    }
}
