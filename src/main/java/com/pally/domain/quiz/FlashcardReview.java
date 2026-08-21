package com.pally.domain.quiz;

import java.time.Instant;

/**
 * One immutable record of a flashcard being rated — the history the
 * {@code flashcards} row destroys on every save.
 *
 * <p>Carries SM-2 state BOTH before and after the rating. The before-state is what
 * makes a genuine repeat review distinguishable from a first attempt: a card rated
 * HARD is reset to {@code repetitions=0, intervalDays=1}, so from the current
 * {@code flashcards} row alone it looks identical to a card nobody ever touched.
 *
 * <p>Instrumentation only. Nothing derives a retention rate from this — see
 * V131's migration comment for why that claim is not yet measurable.
 */
public record FlashcardReview(
        String id,
        String flashcardId,
        String avatarId,
        CardRating rating,
        /** SM-2 q value the scheduler acted on, from {@link Sm2Scheduler#qualityOf}. */
        int quality,
        Instant reviewedAt,

        int prevRepetitions,
        double prevEaseFactor,
        int prevIntervalDays,
        /** Null on a card's first ever review (never scheduled before). */
        Instant prevNextReviewAt,

        int newRepetitions,
        double newEaseFactor,
        int newIntervalDays,
        Instant newNextReviewAt
) {

    /**
     * Builds the history row from the card as it stood before the rating and the
     * card the scheduler produced. Both are taken as arguments rather than
     * recomputed so this can never disagree with what was actually persisted.
     */
    public static FlashcardReview of(String id, FlashCard before, FlashCard after,
                                     CardRating rating, Instant reviewedAt) {
        return new FlashcardReview(
                id,
                before.id(),
                before.avatarId(),
                rating,
                Sm2Scheduler.qualityOf(rating),
                reviewedAt,
                before.repetitions(),
                before.easeFactor(),
                before.intervalDays(),
                before.nextReviewAt(),
                after.repetitions(),
                after.easeFactor(),
                after.intervalDays(),
                after.nextReviewAt());
    }

    /**
     * True when this review found the card already in a successful streak — i.e. a
     * GENUINE repeat recall rather than a first attempt or a post-lapse restart.
     * The single question this table was built to answer; deliberately a derived
     * predicate, not a stored flag, so it cannot drift from the recorded state.
     */
    public boolean isRepeatRecall() {
        return prevRepetitions > 0;
    }
}
