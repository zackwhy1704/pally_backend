package com.pally.domain.quiz.dto;

import com.pally.domain.quiz.QuizQuestion;

import java.util.List;

/**
 * A quiz question as served to the client.
 *
 * <p>GRADE INTEGRITY: {@code correctIndex} is the answer key. The server now
 * grades from a persisted server-side key (it ignores any client-supplied map),
 * so the client no longer NEEDS the key to submit. For a teacher-graded (centre)
 * quiz the key is therefore withheld ({@code null}) — otherwise a student could
 * read the correct answers out of the {@code /quiz/daily} response and replay
 * them, and teacher-visible mastery could not tell "knew it" from "read it".
 * Per-question correctness is returned post-submit instead (see
 * {@code QuizResult.feedback}). For a pure B2C solo-avatar quiz the key is still
 * sent so the app can give instant pre-submit feedback — a stated tradeoff.
 *
 * <p>Build ONLY via {@link #from(QuizQuestion, boolean)} from the serving
 * chokepoint, which decides exposure and persists the server key; an enumeration
 * test enforces that no other path constructs a served question.
 */
public record QuizQuestionResponse(
        String id,
        String question,
        List<String> options,
        String sourcePageSlug,
        Integer correctIndex,
        String explanation
) {
    /**
     * @param exposeKey true for B2C solo quizzes (instant feedback); false for
     *                  teacher-graded centre quizzes (key withheld → null).
     */
    public static QuizQuestionResponse from(QuizQuestion q, boolean exposeKey) {
        return new QuizQuestionResponse(
                q.id(), q.question(), q.options(), q.sourcePageSlug(),
                exposeKey ? q.correctIndex() : null, q.explanation());
    }
}
