package com.pally.api.quiz.dto;

import com.pally.domain.quiz.FlashCard;

import java.time.Instant;

public record FlashcardResponse(
        String id,
        String front,
        String back,
        String sourceSlug,
        String lastRating,
        Instant nextReviewAt,
        int repetitions,
        double easeFactor,
        int intervalDays,
        boolean isDue
) {
    public static FlashcardResponse from(FlashCard card) {
        boolean due = card.nextReviewAt() == null || !card.nextReviewAt().isAfter(Instant.now());
        return new FlashcardResponse(
                card.id(),
                card.front(),
                card.back(),
                card.sourceSlug(),
                card.lastRating() != null ? card.lastRating().name() : null,
                card.nextReviewAt(),
                card.repetitions(),
                card.easeFactor(),
                card.intervalDays(),
                due
        );
    }
}
