package com.pally.domain.quiz;

import java.util.Optional;

/// Domain port for quiz-submit idempotency. The once-only claim + stored result
/// that lets a retried/duplicated submission return the first result instead of
/// re-grading (which would double-credit XP/stars + pollute teacher analytics).
public interface QuizIdempotencyRepository {

    /// Atomically claims (user, key). Throws {@link DuplicateSubmissionException}
    /// if the key was already claimed — the caller then returns the stored result.
    /// Must be inside the grading transaction so a concurrent claim blocks until
    /// the winner commits (with its result), then fails.
    void claim(String userId, String idempotencyKey);

    /// Stores the graded result against a previously-claimed key.
    void storeResult(String userId, String idempotencyKey, QuizResult result);

    /// The stored result for a completed submission, or empty if never claimed or
    /// claimed-but-not-yet-completed.
    Optional<QuizResult> findResult(String userId, String idempotencyKey);
}
