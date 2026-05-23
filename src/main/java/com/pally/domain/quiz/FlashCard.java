package com.pally.domain.quiz;

import java.time.Instant;

public record FlashCard(
        String id,
        String avatarId,
        String front,
        String back,
        String sourceSlug,
        CardRating lastRating,
        Instant nextReviewAt,
        int repetitions,
        double easeFactor,
        int intervalDays
) {
    public FlashCard withRating(CardRating rating, Instant nextReview, int reps, double ef, int interval) {
        return new FlashCard(id, avatarId, front, back, sourceSlug, rating, nextReview, reps, ef, interval);
    }
}
