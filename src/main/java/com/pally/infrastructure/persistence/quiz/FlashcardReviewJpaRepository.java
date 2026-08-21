package com.pally.infrastructure.persistence.quiz;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FlashcardReviewJpaRepository
        extends JpaRepository<FlashcardReviewJpaEntity, String> {

    List<FlashcardReviewJpaEntity> findByFlashcardIdOrderByReviewedAtAsc(String flashcardId);

    long countByAvatarId(String avatarId);
}
