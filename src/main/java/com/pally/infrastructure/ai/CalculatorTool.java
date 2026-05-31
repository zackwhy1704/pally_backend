package com.pally.infrastructure.ai;

import net.objecthunter.exp4j.Expression;
import net.objecthunter.exp4j.ExpressionBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * Deterministic, sandboxed arithmetic evaluator for primary/secondary
 * school maths. Uses exp4j — a pure-Java expression parser; NEVER calls
 * another LLM, never uses ScriptEngine/eval/shell.
 *
 * <p>Whitelisted operators: + − * / ^ %
 * Whitelisted functions: sqrt, abs, floor, ceil, round, log, log2, log10, sin, cos, tan
 * Rejects anything else with a {@link CalculatorException} the model can recover from.
 */
@Component
public class CalculatorTool implements ClaudeTool {

    private static final Logger log = LoggerFactory.getLogger(CalculatorTool.class);

    /** Functions available to the expression evaluator. */
    private static final List<String> ALLOWED_FUNCTIONS =
            List.of("sqrt", "abs", "floor", "ceil", "round", "log", "log2", "log10",
                    "sin", "cos", "tan");

    /** Maximum length of an expression string to prevent DoS. */
    private static final int MAX_EXPR_LENGTH = 500;

    @Override
    public String name() { return "calculator"; }

    @Override
    public String description() {
        return "Evaluates arithmetic expressions deterministically and returns the exact result. "
             + "Call this for ANY numerical computation — never guess arithmetic. "
             + "Supports: +, -, *, /, ^ (power), % (modulo), sqrt(), abs(), "
             + "floor(), ceil(), round(), log(), sin(), cos(), tan(). "
             + "Examples: '37 * 48', 'sqrt(144)', '(3 + 5) / 2', '2^10'.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "expression", Map.of(
                                "type", "string",
                                "description", "A mathematical expression to evaluate, e.g. '37 * 48' or 'sqrt(144)'"
                        )
                ),
                "required", List.of("expression")
        );
    }

    @Override
    public String execute(Map<String, Object> input) throws CalculatorException {
        Object raw = input == null ? null : input.get("expression");
        if (raw == null || raw.toString().isBlank()) {
            throw new CalculatorException("expression is required");
        }
        return evaluate(normalise(raw.toString().trim()));
    }

    /**
     * Evaluates an expression string and returns the result.
     * Public so it can be called directly from the quiz verifier without
     * going through the ClaudeTool dispatch layer.
     */
    public String evaluate(String expression) throws CalculatorException {
        if (expression == null || expression.isBlank()) {
            throw new CalculatorException("expression is required");
        }
        expression = normalise(expression.trim());
        if (expression.length() > MAX_EXPR_LENGTH) {
            throw new CalculatorException("Expression too long (max " + MAX_EXPR_LENGTH + " chars)");
        }
        // Hard-block patterns that could escape the evaluator sandbox.
        // exp4j itself is safe, but being explicit about what we reject.
        if (expression.contains(";") || expression.contains("class ")
                || expression.contains("import ") || expression.contains("exec")
                || expression.contains("eval")) {
            throw new CalculatorException("Unsupported operation in expression");
        }
        try {
            Expression expr = new ExpressionBuilder(expression)
                    .functions(AllowedFunctions.ALL)
                    .build();
            double result = expr.evaluate();
            if (Double.isNaN(result) || Double.isInfinite(result)) {
                throw new CalculatorException("Result is not a finite number — check for division by zero or invalid domain");
            }
            return formatResult(result);
        } catch (CalculatorException e) {
            throw e;
        } catch (Exception e) {
            log.debug("[Calculator] Expression '{}' failed: {}", expression, e.getMessage());
            throw new CalculatorException("Cannot evaluate '" + expression + "': " + e.getMessage());
        }
    }

    /**
     * Formats a double result: strips .0 for whole numbers, rounds to
     * 10 significant figures to avoid floating-point noise (e.g. 0.10000000001).
     */
    private String formatResult(double result) {
        BigDecimal bd = new BigDecimal(result).stripTrailingZeros();
        if (bd.scale() <= 0) {
            return bd.toBigIntegerExact().toString();
        }
        // Round to 10 sig figs to suppress floating-point noise
        bd = new BigDecimal(result).round(new java.math.MathContext(10, RoundingMode.HALF_UP))
                .stripTrailingZeros();
        return bd.toPlainString();
    }

    /**
     * Normalises Unicode math symbols to ASCII so the model can use
     * the × and ÷ characters kids type on mobile keyboards.
     */
    private String normalise(String expr) {
        return expr
                .replace('×', '*')
                .replace('÷', '/')
                .replace('−', '-')   // Unicode minus
                .replace('−', '-'); // another Unicode minus
    }

    // ── Allowed functions (pre-built for performance) ──────────────────

    static final class AllowedFunctions {
        static final net.objecthunter.exp4j.function.Function[] ALL = {
            new net.objecthunter.exp4j.function.Function("sqrt", 1) {
                @Override public double apply(double... args) { return Math.sqrt(args[0]); }
            },
            new net.objecthunter.exp4j.function.Function("abs", 1) {
                @Override public double apply(double... args) { return Math.abs(args[0]); }
            },
            new net.objecthunter.exp4j.function.Function("floor", 1) {
                @Override public double apply(double... args) { return Math.floor(args[0]); }
            },
            new net.objecthunter.exp4j.function.Function("ceil", 1) {
                @Override public double apply(double... args) { return Math.ceil(args[0]); }
            },
            new net.objecthunter.exp4j.function.Function("round", 1) {
                @Override public double apply(double... args) { return Math.round(args[0]); }
            },
            new net.objecthunter.exp4j.function.Function("log", 1) {
                @Override public double apply(double... args) { return Math.log(args[0]); }
            },
            new net.objecthunter.exp4j.function.Function("log2", 1) {
                @Override public double apply(double... args) { return Math.log(args[0]) / Math.log(2); }
            },
            new net.objecthunter.exp4j.function.Function("log10", 1) {
                @Override public double apply(double... args) { return Math.log10(args[0]); }
            },
            new net.objecthunter.exp4j.function.Function("sin", 1) {
                @Override public double apply(double... args) { return Math.sin(args[0]); }
            },
            new net.objecthunter.exp4j.function.Function("cos", 1) {
                @Override public double apply(double... args) { return Math.cos(args[0]); }
            },
            new net.objecthunter.exp4j.function.Function("tan", 1) {
                @Override public double apply(double... args) { return Math.tan(args[0]); }
            },
        };
    }

    public static class CalculatorException extends Exception {
        public CalculatorException(String message) { super(message); }
    }
}
