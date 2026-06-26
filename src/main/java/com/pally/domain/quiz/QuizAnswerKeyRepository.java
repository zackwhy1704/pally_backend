package com.pally.domain.quiz;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Server-held answer key for generated quiz questions.
 *
 * <p>Grade integrity: the correct index for each question is persisted at
 * generation time so the submit path can grade against the SERVER's key
 * instead of a client-supplied {@code correctMap}. Without this, a tampered
 * client could post an all-correct map and inflate the centre's teacher-visible
 * "grasp" analytics (which are {@code AVG(was_correct)} over quiz results).
 *
 * <p>Persisted (not an in-memory cache) so the key survives a restart or a
 * second instance — a kid may fetch the quiz on one instance and submit to
 * another.
 */
public interface QuizAnswerKeyRepository {

    /**
     * The server-held answer data a teacher-graded quiz withholds from the
     * client and grades/reveals from instead: the correct option and the
     * explanation (both reveal the answer, so both are withheld pre-submit).
     */
    record AnswerKey(int correctIndex, String explanation) {}

    /**
     * Persists the correct index AND explanation for each generated question.
     * Idempotent on question id (re-generation overwrites), so a regenerated
     * quiz keeps a single authoritative key per question.
     */
    void saveKeys(String avatarId, List<QuizQuestion> questions);

    /**
     * Returns {@code questionId → AnswerKey} for the given ids, containing only
     * the ids that have a persisted key. A missing id signals "no server key"
     * so the caller can decide its fallback.
     */
    Map<String, AnswerKey> findByQuestionIds(Collection<String> questionIds);

    /**
     * Deletes answer keys created before {@code cutoff}. A daily reaper calls
     * this so the table doesn't grow unbounded — keys are only useful for the
     * day's quiz, so anything past the retention window is dead weight at scale
     * (~5 rows × every quiz × every student × every day).
     *
     * @return the number of keys removed
     */
    int deleteOlderThan(Instant cutoff);
}
