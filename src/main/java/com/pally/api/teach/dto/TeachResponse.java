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
        int newLevel,
        Status status
) {
    /** Whether the evaluator actually produced a grade. EVAL_FAILED (parse/blank/
     *  exception/too-short) must render as a retry, NEVER a 0/0 score card. */
    public enum Status { OK, EVAL_FAILED }

    /** Back-compat ctor for the evaluator which doesn't know about leveling.
     *  A plain evaluation is OK. */
    public TeachResponse(int score, int totalConcepts, int xpEarned,
                         List<String> coveredConcepts,
                         List<String> missedConcepts,
                         String followUpQuestion, String feedback) {
        this(score, totalConcepts, xpEarned, coveredConcepts, missedConcepts,
                followUpQuestion, feedback, false, 0, Status.OK);
    }

    /** No grade was produced — the client shows a retry, not a score. Nothing
     *  should persist (no XP: xpEarned is 0; no certainty: totalConcepts is 0). */
    public static TeachResponse evalFailed(String feedback) {
        return new TeachResponse(0, 0, 0, List.of(), List.of(), null, feedback,
                false, 0, Status.EVAL_FAILED);
    }

    /** Returns a copy with the level signals populated (status preserved). */
    public TeachResponse withLevel(boolean levelledUp, int newLevel) {
        return new TeachResponse(score, totalConcepts, xpEarned,
                coveredConcepts, missedConcepts, followUpQuestion, feedback,
                levelledUp, newLevel, status);
    }
}
