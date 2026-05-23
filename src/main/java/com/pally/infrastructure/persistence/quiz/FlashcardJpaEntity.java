package com.pally.infrastructure.persistence.quiz;

import com.pally.domain.quiz.CardRating;
import com.pally.domain.quiz.FlashCard;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "flashcards")
@Getter
@Setter
@NoArgsConstructor
public class FlashcardJpaEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "avatar_id", nullable = false, length = 36)
    private String avatarId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String front;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String back;

    @Column(name = "source_slug", length = 200)
    private String sourceSlug;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_rating", length = 10)
    private CardRating lastRating;

    @Column(name = "next_review_at")
    private Instant nextReviewAt;

    @Column(nullable = false)
    private int repetitions;

    @Column(name = "ease_factor", nullable = false)
    private double easeFactor;

    @Column(name = "interval_days", nullable = false)
    private int intervalDays;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static FlashcardJpaEntity fromDomain(FlashCard card) {
        FlashcardJpaEntity e = new FlashcardJpaEntity();
        e.id = card.id();
        e.avatarId = card.avatarId();
        e.front = card.front();
        e.back = card.back();
        e.sourceSlug = card.sourceSlug();
        e.lastRating = card.lastRating();
        e.nextReviewAt = card.nextReviewAt();
        e.repetitions = card.repetitions();
        e.easeFactor = card.easeFactor();
        e.intervalDays = card.intervalDays();
        e.createdAt = Instant.now();
        return e;
    }

    public FlashCard toDomain() {
        return new FlashCard(id, avatarId, front, back, sourceSlug, lastRating, nextReviewAt,
                repetitions, easeFactor, intervalDays);
    }
}
