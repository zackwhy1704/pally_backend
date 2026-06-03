package com.pally.infrastructure.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for AlgebraTool — quadratic roots, polynomial derivatives, and vector magnitudes.
 */
class AlgebraToolTest {

    private AlgebraTool tool;

    @BeforeEach
    void setUp() {
        tool = new AlgebraTool();
    }

    // ── quadraticRoots ────────────────────────────────────────────────────────

    @Test
    void quadraticRoots_twoDistinctIntegerRoots() {
        // x² + 5x + 6 = 0 → (x+2)(x+3) → roots: x = -3 or x = -2
        String result = tool.quadraticRoots(1, 5, 6);
        assertThat(result)
                .contains("-3")
                .contains("-2")
                .contains("or");
    }

    @Test
    void quadraticRoots_doubleRoot() {
        // x² - 4x + 4 = 0 → (x-2)² → root: x = 2 (double)
        String result = tool.quadraticRoots(1, -4, 4);
        assertThat(result)
                .contains("2")
                .contains("double root");
    }

    @Test
    void quadraticRoots_negativeDiscriminant_noRealRoots() {
        // x² + 1 = 0 → no real roots
        String result = tool.quadraticRoots(1, 0, 1);
        assertThat(result).contains("no real roots");
    }

    @Test
    void quadraticRoots_leadingCoeffNot1() {
        // 2x² - 5x - 3 = 0 → roots: x = -0.5 or x = 3
        String result = tool.quadraticRoots(2, -5, -3);
        assertThat(result)
                .contains("3")
                .contains("or");
    }

    @ParameterizedTest(name = "a={0}, b={1}, c={2} → contains ''{3}''")
    @CsvSource({
        "1, -5, 6,  '3'",      // x²-5x+6=0 → roots 2,3
        "1, -5, 6,  '2'",
        "1, 0, -9,  '3'",      // x²-9=0 → roots ±3
        "1, 0, -9,  '-3'",
    })
    void quadraticRoots_parameterised(double a, double b, double c, String expected) {
        assertThat(tool.quadraticRoots(a, b, c)).contains(expected);
    }

    // ── derivative ────────────────────────────────────────────────────────────

    @Test
    void derivative_simplePolynomial_powerRule() {
        // d/dx(3x²) = 6x
        String result = tool.derivative("3x^2");
        assertThat(result).contains("6x");
    }

    @Test
    void derivative_polynomialPlusTerm() {
        // d/dx(3x^2 + 2x + 5) = 6x + 2
        String result = tool.derivative("3x^2 + 2x + 5");
        assertThat(result)
                .contains("6x")
                .contains("2");
        // Constant 5 should vanish
        assertThat(result).doesNotContain("5");
    }

    @Test
    void derivative_unicodeSuperscript() {
        // 3x² + 2x using ² symbol
        String result = tool.derivative("3x² + 2x");
        assertThat(result)
                .contains("6x")
                .contains("2");
    }

    @Test
    void derivative_constantOnly_returnsZero() {
        String result = tool.derivative("7");
        assertThat(result).isEqualTo("0");
    }

    @Test
    void derivative_singleX_returnsOne() {
        // d/dx(x) = 1
        String result = tool.derivative("x");
        assertThat(result).isEqualTo("1");
    }

    @Test
    void derivative_nullOrBlank_returnsEmpty() {
        assertThat(tool.derivative(null)).isEmpty();
        assertThat(tool.derivative("   ")).isEmpty();
    }

    // ── vectorMagnitude ───────────────────────────────────────────────────────

    @Test
    void vectorMagnitude_pythagoreanTriple_345() {
        // |(3, 4)| = 5
        String result = tool.vectorMagnitude(3, 4);
        assertThat(result)
                .contains("5")
                .contains("√");
    }

    @Test
    void vectorMagnitude_pythagoreanTriple_512_13() {
        // |(5, 12)| = 13
        String result = tool.vectorMagnitude(5, 12);
        assertThat(result).contains("13");
    }

    @Test
    void vectorMagnitude_irrationalResult_containsDecimal() {
        // |(1, 1)| = √2 ≈ 1.4142
        String result = tool.vectorMagnitude(1, 1);
        assertThat(result)
                .contains("√")
                .contains("2");
    }
}
