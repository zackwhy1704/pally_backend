package com.pally.domain.quiz;

/// Thrown when a quiz submit claims an idempotency key that another submission
/// already claimed — i.e. this POST is a retry/race of one client action. The
/// caller catches it and returns the winner's stored result (never re-grades).
public class DuplicateSubmissionException extends RuntimeException {
    public DuplicateSubmissionException(String userId, String idempotencyKey) {
        super("Quiz submission already claimed for user=" + userId + " key=" + idempotencyKey);
    }
}
