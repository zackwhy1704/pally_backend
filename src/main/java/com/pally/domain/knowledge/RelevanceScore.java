package com.pally.domain.knowledge;

/**
 * Value object representing the outcome of a relevance check.
 *
 * @param value         relevance score in range [0.0, 1.0] (topic relevance)
 * @param reason        short human-readable explanation from the AI
 * @param studyMaterial A2 — false when the upload is NOT educational/study material
 *                      at all (a receipt, selfie, blank page, unrelated doc), as
 *                      opposed to merely off-topic. Defaults true (fail-open).
 */
public record RelevanceScore(double value, String reason, boolean studyMaterial) {

    private static final double RELEVANCE_THRESHOLD = 0.45;

    /** Back-compat: assume the content IS study material unless classified otherwise. */
    public RelevanceScore(double value, String reason) {
        this(value, reason, true);
    }

    public boolean isRelevant() {
        return value >= RELEVANCE_THRESHOLD;
    }
}
