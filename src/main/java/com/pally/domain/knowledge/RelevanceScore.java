package com.pally.domain.knowledge;

/**
 * Value object representing the outcome of a relevance check.
 *
 * @param value  relevance score in range [0.0, 1.0]
 * @param reason short human-readable explanation from the AI
 */
public record RelevanceScore(double value, String reason) {

    private static final double RELEVANCE_THRESHOLD = 0.45;

    public boolean isRelevant() {
        return value >= RELEVANCE_THRESHOLD;
    }
}
