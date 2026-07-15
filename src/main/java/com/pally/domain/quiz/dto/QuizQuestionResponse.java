package com.pally.domain.quiz.dto;

import com.pally.domain.quiz.QuizQuestion;

import java.util.List;

/**
 * A quiz question as served to the client.
 *
 * <p>GRADE INTEGRITY: two fields reveal the answer — {@code correctIndex} (the
 * option) and {@code explanation} (which justifies it, e.g. "3 out of 8"). The
 * server grades + explains from a persisted server-side key, so the client needs
 * neither to submit. For a teacher-graded (centre) quiz BOTH are withheld
 * ({@code null}) — otherwise a student could read the answer out of the
 * {@code /quiz/daily} response and replay it, and teacher-visible mastery could
 * not tell "knew it" from "read it". Both are returned post-submit instead (see
 * {@code QuizResult.feedback}). For a pure B2C solo-avatar quiz they are sent so
 * the app can give instant pre-submit feedback — a stated tradeoff.
 *
 * <p>SAFE fields ({@code id}, {@code question}, {@code options},
 * {@code sourcePageSlug}) are always sent: they don't reveal the correct option.
 * {@code sourcePageSlug} is a topic pointer, not the answer (the question text
 * usually names the topic anyway). A NEW field on this DTO must be classified
 * safe-or-withheld — {@code GradableQuizExposureTest} fails until it is.
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
        // Provenance metadata (SAFE — never reveals the correct option): the source page
        // title (for a "from your notes" chip) and why this question was chosen
        // ("WEAK_TOPIC:{concept}" when weak-first selected it, else null).
        String pageTitle,
        String selectionReason,
        Integer correctIndex,
        String explanation
) {
    /**
     * @param exposeKey true for B2C solo quizzes (instant feedback); false for
     *                  teacher-graded centre quizzes (answer-revealing fields
     *                  withheld → null). ONE decision drives every withheld
     *                  field, so a new answer-revealing field is withheld here.
     */
    public static QuizQuestionResponse from(QuizQuestion q, boolean exposeKey) {
        return new QuizQuestionResponse(
                q.id(), q.question(), q.options(), q.sourcePageSlug(),
                q.sourcePageTitle(), q.selectionReason(),   // SAFE provenance — always sent
                exposeKey ? q.correctIndex() : null,
                exposeKey ? q.explanation() : null);
    }
}
