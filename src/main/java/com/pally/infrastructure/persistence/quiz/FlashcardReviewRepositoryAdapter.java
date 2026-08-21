package com.pally.infrastructure.persistence.quiz;

import com.pally.domain.quiz.FlashcardReview;
import com.pally.domain.quiz.FlashcardReviewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Maps between {@link FlashcardReview} and its JPA row. JPA entities never leave
 * this class.
 */
@Component
@RequiredArgsConstructor
public class FlashcardReviewRepositoryAdapter implements FlashcardReviewRepository {

    private final FlashcardReviewJpaRepository jpa;

    @Override
    @Transactional
    public FlashcardReview save(FlashcardReview review) {
        return toDomain(jpa.save(toEntity(review)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<FlashcardReview> findByFlashcardIdOrderByReviewedAt(String flashcardId) {
        return jpa.findByFlashcardIdOrderByReviewedAtAsc(flashcardId)
                .stream().map(this::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long countByAvatarId(String avatarId) {
        return jpa.countByAvatarId(avatarId);
    }

    private FlashcardReviewJpaEntity toEntity(FlashcardReview r) {
        FlashcardReviewJpaEntity e = new FlashcardReviewJpaEntity();
        e.setId(r.id());
        e.setFlashcardId(r.flashcardId());
        e.setAvatarId(r.avatarId());
        e.setRating(r.rating());
        e.setQuality(r.quality());
        e.setReviewedAt(r.reviewedAt());
        e.setPrevRepetitions(r.prevRepetitions());
        e.setPrevEaseFactor(r.prevEaseFactor());
        e.setPrevIntervalDays(r.prevIntervalDays());
        e.setPrevNextReviewAt(r.prevNextReviewAt());
        e.setNewRepetitions(r.newRepetitions());
        e.setNewEaseFactor(r.newEaseFactor());
        e.setNewIntervalDays(r.newIntervalDays());
        e.setNewNextReviewAt(r.newNextReviewAt());
        return e;
    }

    private FlashcardReview toDomain(FlashcardReviewJpaEntity e) {
        return new FlashcardReview(
                e.getId(), e.getFlashcardId(), e.getAvatarId(), e.getRating(),
                e.getQuality(), e.getReviewedAt(),
                e.getPrevRepetitions(), e.getPrevEaseFactor(), e.getPrevIntervalDays(),
                e.getPrevNextReviewAt(),
                e.getNewRepetitions(), e.getNewEaseFactor(), e.getNewIntervalDays(),
                e.getNewNextReviewAt());
    }
}
