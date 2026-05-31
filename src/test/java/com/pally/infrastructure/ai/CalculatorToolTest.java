package com.pally.infrastructure.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class CalculatorToolTest {

    private CalculatorTool calc;

    @BeforeEach
    void setUp() { calc = new CalculatorTool(); }

    // ── Basic operations ──────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource({
        "2 + 2,         4",
        "10 - 3,        7",
        "37 * 48,       1776",
        "100 / 4,       25",
        "2 ^ 8,         256",
        "15 % 4,        3",
        "1000 / 8,      125",
        "347 * 89,      30883",  // hard — model often gets this wrong
        "1024 / 32,     32",
        "(3 + 5) * 2,   16",
    })
    void basicArithmetic(String expr, String expected) throws Exception {
        assertThat(calc.evaluate(expr)).isEqualTo(expected);
    }

    // ── Order of operations ───────────────────────────────────────────────────

    @Test
    void orderOfOperations_multiplicationBeforeAddition() throws Exception {
        assertThat(calc.evaluate("2 + 3 * 4")).isEqualTo("14");
    }

    @Test
    void orderOfOperations_parenthesesOverrideDefault() throws Exception {
        assertThat(calc.evaluate("(2 + 3) * 4")).isEqualTo("20");
    }

    @Test
    void orderOfOperations_nestedParentheses() throws Exception {
        assertThat(calc.evaluate("((2 + 3) * (1 + 1))")).isEqualTo("10");
    }

    // ── Functions ─────────────────────────────────────────────────────────────

    @Test
    void sqrt_perfectSquare() throws Exception {
        assertThat(calc.evaluate("sqrt(144)")).isEqualTo("12");
    }

    @Test
    void sqrt_nonPerfectSquare_roundsToTenSigFigs() throws Exception {
        double expected = Math.sqrt(2);
        double result = Double.parseDouble(calc.evaluate("sqrt(2)"));
        assertThat(result).isCloseTo(expected, within(1e-9));
    }

    @Test
    void abs_negativeValue() throws Exception {
        assertThat(calc.evaluate("abs(-42)")).isEqualTo("42");
    }

    @Test
    void floor_decimalDown() throws Exception {
        assertThat(calc.evaluate("floor(3.7)")).isEqualTo("3");
    }

    @Test
    void ceil_decimalUp() throws Exception {
        assertThat(calc.evaluate("ceil(3.1)")).isEqualTo("4");
    }

    // ── Fractions and decimals ────────────────────────────────────────────────

    @Test
    void division_producesDecimal() throws Exception {
        assertThat(calc.evaluate("1 / 3"))
                .satisfies(s -> assertThat(Double.parseDouble(s)).isCloseTo(0.3333, within(1e-3)));
    }

    @Test
    void decimalArithmetic() throws Exception {
        assertThat(calc.evaluate("2.5 * 4")).isEqualTo("10");
    }

    // ── Unicode math symbols (kids' mobile keyboards) ─────────────────────────

    @Test
    void unicodeMultiply_timesSymbol() throws Exception {
        assertThat(calc.evaluate("6 × 7")).isEqualTo("42");
    }

    @Test
    void unicodeDivide_divisionSymbol() throws Exception {
        assertThat(calc.evaluate("56 ÷ 8")).isEqualTo("7");
    }

    // ── Negative numbers ──────────────────────────────────────────────────────

    @Test
    void negativeNumber_subtraction() throws Exception {
        assertThat(calc.evaluate("-5 + 3")).isEqualTo("-2");
    }

    @Test
    void negativeNumber_multiplication() throws Exception {
        assertThat(calc.evaluate("-3 * 4")).isEqualTo("-12");
    }

    // ── Error handling ────────────────────────────────────────────────────────

    @Test
    void divisionByZero_throwsCalculatorException() {
        assertThatThrownBy(() -> calc.evaluate("1 / 0"))
                .isInstanceOf(CalculatorTool.CalculatorException.class);
    }

    @Test
    void malformedExpression_throwsCalculatorException() {
        assertThatThrownBy(() -> calc.evaluate("2 ++ * 2"))
                .isInstanceOf(CalculatorTool.CalculatorException.class);
    }

    @Test
    void emptyExpression_throwsCalculatorException() {
        assertThatThrownBy(() -> calc.evaluate(""))
                .isInstanceOf(CalculatorTool.CalculatorException.class);
    }

    @Test
    void nullExpression_throwsViaTool() {
        assertThatThrownBy(() -> calc.execute(null))
                .isInstanceOf(CalculatorTool.CalculatorException.class);
    }

    @Test
    void injectionAttempt_semicolonRejected() {
        assertThatThrownBy(() -> calc.evaluate("1; Runtime.getRuntime()"))
                .isInstanceOf(CalculatorTool.CalculatorException.class);
    }

    @Test
    void injectionAttempt_classKeywordRejected() {
        assertThatThrownBy(() -> calc.evaluate("class Foo{}"))
                .isInstanceOf(CalculatorTool.CalculatorException.class);
    }

    // ── ClaudeTool interface ──────────────────────────────────────────────────

    @Test
    void toolName_isCalculator() {
        assertThat(calc.name()).isEqualTo("calculator");
    }

    @Test
    void inputSchema_hasExpressionProperty() {
        var schema = calc.inputSchema();
        assertThat(schema.containsKey("properties")).isTrue();
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> props =
                (java.util.Map<String, Object>) schema.get("properties");
        assertThat(props.containsKey("expression")).isTrue();
    }

    @Test
    void execute_viaMap_works() throws Exception {
        String result = calc.execute(Map.of("expression", "6 * 7"));
        assertThat(result).isEqualTo("42");
    }
}
