package com.pally.api.teach.dto;

import java.util.List;

/**
 * Feedback after the student teaches a topic to the avatar.
 *
 * @param coveredConcepts  concepts the student mentioned correctly
 * @param missedConcepts   concepts the student left out
 * @param followUpQuestion one Socratic question targeting the largest gap,
 *                          or {@code null} when the student covered everything
 * @param levelledUp       {@code true} when this credit pushed the user
 *                          across a level threshold
 * @param newLevel         user's level after the credit
 */
public record TeachResponse(
        int score,
        int totalConcepts,
        int xpEarned,
        List<String> coveredConcepts,
        List<String> missedConcepts,
        String followUpQuestion,
        String feedback,
        boolean levelledUp,
        int newLevel
) {
    /** Back-compat ctor for the evaluator which doesn't know about leveling. */
    public TeachResponse(int score, int totalConcepts, int xpEarned,
                         List<String> coveredConcepts,
                         List<String> missedConcepts,
                         String followUpQuestion, String feedback) {
        this(score, totalConcepts, xpEarned, coveredConcepts, missedConcepts,
                followUpQuestion, feedback, false, 0);
    }

    /** Returns a copy with the level signals populated. */
    public TeachResponse withLevel(boolean levelledUp, int newLevel) {
        return new TeachResponse(score, totalConcepts, xpEarned,
                coveredConcepts, missedConcepts, followUpQuestion, feedback,
                levelledUp, newLevel);
    }
}
