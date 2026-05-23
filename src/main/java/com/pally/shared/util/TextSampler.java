package com.pally.shared.util;

/**
 * Extracts the first N approximate tokens from a text blob for use
 * in relevance-check prompts. Uses a whitespace-split heuristic
 * (1 token ≈ 4 characters on average for English text).
 */
public final class TextSampler {

    private static final int DEFAULT_TOKEN_LIMIT = 500;
    private static final int AVG_CHARS_PER_TOKEN = 4;

    private TextSampler() {}

    /**
     * Returns the first {@code tokenLimit} approximate tokens from {@code text}.
     *
     * @param text       raw extracted text
     * @param tokenLimit maximum number of tokens to include
     * @return truncated text (never null)
     */
    public static String sample(String text, int tokenLimit) {
        if (text == null || text.isBlank()) return "";
        int charLimit = tokenLimit * AVG_CHARS_PER_TOKEN;
        if (text.length() <= charLimit) return text;

        // Find the last whitespace boundary within the char limit
        String candidate = text.substring(0, charLimit);
        int lastSpace = candidate.lastIndexOf(' ');
        return lastSpace > 0 ? candidate.substring(0, lastSpace) : candidate;
    }

    /**
     * Returns the first {@value #DEFAULT_TOKEN_LIMIT} approximate tokens from {@code text}.
     */
    public static String sample(String text) {
        return sample(text, DEFAULT_TOKEN_LIMIT);
    }
}
