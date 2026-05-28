package com.pally.domain.quiz;

import java.util.List;

/**
 * Quiz outcome. When confidence ratings were provided on the request, the
 * {@code masteryMatrix} is non-null and carries each question's classification
 * into one of four quadrants:
 *
 * <ul>
 *   <li><b>mastered</b> — correct + confident</li>
 *   <li><b>misconception</b> — wrong + confident (highest priority to fix)</li>
 *   <li><b>luckyGuess</b> — correct + not confident</li>
 *   <li><b>knownGap</b> — wrong + not confident</li>
 * </ul>
 */
public record QuizResult(
        String sessionId,
        int score,
        int total,
        int xpEarned,
        int starsEarned,
        boolean levelledUp,
        int newLevel,
        MasteryMatrix masteryMatrix
) {

    public QuizResult(String sessionId, int score, int total, int xpEarned,
                      int starsEarned, boolean levelledUp, int newLevel) {
        this(sessionId, score, total, xpEarned, starsEarned, levelledUp,
                newLevel, null);
    }

    /** Lists hold the slug (or questionId fallback) per quadrant. */
    public record MasteryMatrix(
            List<String> mastered,
            List<String> misconception,
            List<String> luckyGuess,
            List<String> knownGap,
            String priorityReview
    ) {}
}
