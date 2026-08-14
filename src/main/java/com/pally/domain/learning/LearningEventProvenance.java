package com.pally.domain.learning;

/**
 * Trust tier of a {@link LearningEvent}, mirroring each source table's own
 * trust-typing ({@code GradingSignal} for module_progress; quiz and flashcard
 * have no equivalent column today).
 */
public enum LearningEventProvenance {
    VERIFIED_SERVER_GRADED,
    SPACED_VERIFIED_RECALL,
    SELF_REPORT
}
