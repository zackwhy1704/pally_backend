package com.pally.infrastructure.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Simple algebra solver for polynomial derivatives and quadratic roots.
 *
 * <p>This is a plain string-manipulation + deterministic maths tool — it does
 * NOT call any external API or LLM. It is called by
 * {@link ClaudeContextAssembler#injectArithmeticVerification} to pre-compute
 * algebra facts so Claude sees the verified answer rather than guessing.
 *
 * <p>Supported operations:
 * <ul>
 *   <li>{@link #derivative(String)} — power rule on simple polynomials like
 *       {@code 3x^2 + 2x + 5}</li>
 *   <li>{@link #quadraticRoots(double, double, double)} — quadratic formula
 *       applied to ax² + bx + c = 0</li>
 * </ul>
 */
@Component
public class AlgebraTool {

    private static final Logger log = LoggerFactory.getLogger(AlgebraTool.class);

    // Matches individual polynomial terms: e.g. "3x^2", "-2x", "+5", "x^3", "x"
    // Group 1 = coefficient (optional, may include sign)
    // Group 2 = exponent (optional)
    private static final Pattern TERM_PATTERN = Pattern.compile(
            "([+-]?\\s*\\d*\\.?\\d*)\\s*x(?:\\^(\\d+))?|([+-]?\\s*\\d+\\.?\\d*)",
            Pattern.CASE_INSENSITIVE);

    /**
     * Applies the power rule to a simple polynomial and returns the derivative
     * as a human-readable string.
     *
     * <p>Examples:
     * <pre>
     *   derivative("3x^2 + 2x + 5")  → "6x + 2"
     *   derivative("x^3 - 4x")        → "3x^2 - 4"
     *   derivative("7x^4 + x^2 - 3") → "28x^3 + 2x"
     * </pre>
     *
     * @param expression the polynomial expression (no "d/dx" prefix needed)
     * @return formatted derivative string, or empty string if parsing fails
     */
    public String derivative(String expression) {
        if (expression == null || expression.isBlank()) return "";
        try {
            // Normalise: replace × with *, remove spaces around ^
            String norm = expression.trim()
                    .replace("²", "^2")
                    .replace("³", "^3")
                    .replace("×", "*");

            List<String> resultTerms = new ArrayList<>();
            Matcher m = TERM_PATTERN.matcher(norm);

            while (m.find()) {
                if (m.group(3) != null) {
                    // Constant term — derivative is 0, skip
                    continue;
                }
                // Term with x
                String coeffStr = m.group(1) == null ? "" : m.group(1).replaceAll("\\s+", "");
                String expStr   = m.group(2); // null means exponent is 1 (bare x)

                double coeff = parseCoeff(coeffStr);
                int exp = expStr == null ? 1 : Integer.parseInt(expStr);

                double newCoeff = coeff * exp;
                int newExp = exp - 1;

                if (newCoeff == 0) continue;

                String term;
                if (newExp == 0) {
                    // Constant result — always show the number (never omit "1")
                    term = formatConstant(newCoeff, resultTerms.isEmpty());
                } else if (newExp == 1) {
                    term = formatCoeff(newCoeff, resultTerms.isEmpty()) + "x";
                } else {
                    term = formatCoeff(newCoeff, resultTerms.isEmpty()) + "x^" + newExp;
                }
                resultTerms.add(term);
            }

            if (resultTerms.isEmpty()) return "0";
            return String.join(" ", resultTerms)
                    .replaceAll("\\s+\\+\\s+", " + ")
                    .replaceAll("\\s+-\\s+", " - ")
                    .trim();

        } catch (Exception e) {
            log.debug("[AlgebraTool] derivative() failed for '{}': {}", expression, e.getMessage());
            return "";
        }
    }

    /**
     * Applies the quadratic formula to ax² + bx + c = 0.
     *
     * @param a coefficient of x²
     * @param b coefficient of x
     * @param c constant
     * @return human-readable roots string, e.g. "x = -2 or x = -3", "x = 3 (double root)",
     *         or "no real roots"
     */
    public String quadraticRoots(double a, double b, double c) {
        try {
            double discriminant = b * b - 4 * a * c;
            if (discriminant < 0) {
                return "no real roots (discriminant = " + round(discriminant) + " < 0)";
            }
            double x1 = (-b + Math.sqrt(discriminant)) / (2 * a);
            double x2 = (-b - Math.sqrt(discriminant)) / (2 * a);
            if (discriminant == 0) {
                return "x = " + round(x1) + " (double root)";
            }
            // Present smaller root second for readability
            if (x1 > x2) {
                double tmp = x1; x1 = x2; x2 = tmp;
            }
            return "x = " + round(x1) + " or x = " + round(x2);
        } catch (Exception e) {
            log.debug("[AlgebraTool] quadraticRoots() failed a={} b={} c={}: {}", a, b, c, e.getMessage());
            return "";
        }
    }

    /**
     * Computes the vector magnitude √(a² + b²) and returns a formatted string.
     *
     * @param a first component
     * @param b second component
     * @return formatted string e.g. "√(3² + 4²) = √25 = 5"
     */
    public String vectorMagnitude(double a, double b) {
        try {
            double sumSq = a * a + b * b;
            double mag = Math.sqrt(sumSq);
            String sumSqStr = round(sumSq);
            String magStr = round(mag);
            return "√(" + round(a) + "² + " + round(b) + "²) = √" + sumSqStr + " = " + magStr;
        } catch (Exception e) {
            log.debug("[AlgebraTool] vectorMagnitude() failed a={} b={}: {}", a, b, e.getMessage());
            return "";
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Parses a coefficient string, defaulting to 1 for empty/bare "+" or "-". */
    private double parseCoeff(String s) {
        if (s == null || s.isBlank() || s.equals("+")) return 1.0;
        if (s.equals("-")) return -1.0;
        return Double.parseDouble(s.trim());
    }

    /** Formats a constant (no variable following) — never omits the "1". */
    private String formatConstant(double c, boolean isFirst) {
        String prefix = isFirst ? "" : (c >= 0 ? " + " : " - ");
        double abs = Math.abs(c);
        BigDecimal bd = new BigDecimal(abs).stripTrailingZeros();
        String num = bd.scale() <= 0 ? bd.toBigIntegerExact().toString() : bd.toPlainString();
        if (!isFirst) return prefix + num;
        return (c < 0 ? "-" : "") + num;
    }

    /** Formats a coefficient for display; omits "1" when the variable follows. */
    private String formatCoeff(double c, boolean isFirst) {
        String prefix = isFirst ? "" : (c >= 0 ? " + " : " - ");
        double abs = Math.abs(c);
        String num;
        if (abs == 1.0) {
            num = isFirst ? (c < 0 ? "-" : "") : "";
        } else {
            BigDecimal bd = new BigDecimal(abs).stripTrailingZeros();
            num = bd.scale() <= 0 ? bd.toBigIntegerExact().toString() : bd.toPlainString();
        }
        if (!isFirst) {
            // prefix already encodes sign
            return prefix + num;
        }
        return (c < 0 ? "-" : "") + num;
    }

    /** Rounds to 4 decimal places for display, strips trailing zeros. */
    private String round(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return String.valueOf(d);
        BigDecimal bd = new BigDecimal(d)
                .round(new MathContext(10, RoundingMode.HALF_UP))
                .stripTrailingZeros();
        if (bd.scale() <= 0) {
            try { return bd.toBigIntegerExact().toString(); }
            catch (ArithmeticException ignored) { /* fall through */ }
        }
        // Cap at 4 decimal places
        if (bd.scale() > 4) {
            bd = bd.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros();
        }
        return bd.toPlainString();
    }
}
