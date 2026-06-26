package com.pally.shared.util;

/**
 * Code-point-safe truncation for values that flow from unbounded LLM output into a
 * bounded {@code VARCHAR(n)} column.
 *
 * <p>Postgres {@code VARCHAR(n)} counts characters (Unicode code points). A raw
 * {@link String#substring} counts UTF-16 units, so it can BOTH overshoot the column
 * (a supplementary char is 2 units but 1 column character) AND split a surrogate
 * pair, yielding an unpaired surrogate that is invalid UTF-8 and fails the write
 * with an encoding error → DataIntegrity. This clamps by code points and never
 * splits a pair — the safe way to bound any identifier-ish column written from
 * generated text. Free-text columns should be {@code TEXT}, not clamped.
 */
public final class TextClamp {

    private TextClamp() {}

    /** Truncate to at most {@code maxChars} Unicode characters, never splitting a pair. */
    public static String toCodePoints(String s, int maxChars) {
        if (s == null || s.codePointCount(0, s.length()) <= maxChars) {
            return s;
        }
        return s.substring(0, s.offsetByCodePoints(0, maxChars));
    }
}
