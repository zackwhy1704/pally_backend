package com.pally.domain.module;

/**
 * A student's self-assessment of their own open-ended (PROVE) answer, shown
 * against the reference answer. Low-trust by design — the system never asserts
 * correctness for open-ended work; the student does, and it's weighted down
 * (see {@link GradingWeights}). Maps to a mastery score.
 */
public enum SelfReport {
    YES(1.0),
    PARTLY(0.5),
    NO(0.0);

    private final double score;
    SelfReport(double score) { this.score = score; }
    public double score() { return score; }
}
