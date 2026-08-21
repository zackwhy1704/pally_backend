package com.pally.infrastructure.persistence.quiz;

import com.pally.domain.quiz.CardRating;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** JPA row for one flashcard review (V131). Never leaves infrastructure. */
@Entity
@Table(
        name = "flashcard_review",
        indexes = {
                @Index(name = "idx_flashcard_review_card", columnList = "flashcard_id,reviewed_at"),
                @Index(name = "idx_flashcard_review_avatar", columnList = "avatar_id,reviewed_at")
        })
@Getter
@Setter
@NoArgsConstructor
public class FlashcardReviewJpaEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "flashcard_id", nullable = false, length = 36)
    private String flashcardId;

    @Column(name = "avatar_id", nullable = false, length = 36)
    private String avatarId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CardRating rating;

    @Column(nullable = false)
    private int quality;

    @Column(name = "reviewed_at", nullable = false)
    private Instant reviewedAt;

    @Column(name = "prev_repetitions", nullable = false)
    private int prevRepetitions;

    @Column(name = "prev_ease_factor", nullable = false)
    private double prevEaseFactor;

    @Column(name = "prev_interval_days", nullable = false)
    private int prevIntervalDays;

    /** Null on a card's first ever review. */
    @Column(name = "prev_next_review_at")
    private Instant prevNextReviewAt;

    @Column(name = "new_repetitions", nullable = false)
    private int newRepetitions;

    @Column(name = "new_ease_factor", nullable = false)
    private double newEaseFactor;

    @Column(name = "new_interval_days", nullable = false)
    private int newIntervalDays;

    @Column(name = "new_next_review_at", nullable = false)
    private Instant newNextReviewAt;
}
