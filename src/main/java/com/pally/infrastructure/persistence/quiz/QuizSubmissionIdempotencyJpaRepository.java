package com.pally.infrastructure.persistence.quiz;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface QuizSubmissionIdempotencyJpaRepository
        extends JpaRepository<QuizSubmissionIdempotencyJpaEntity, String> {

    Optional<QuizSubmissionIdempotencyJpaEntity> findByUserIdAndIdempotencyKey(
            String userId, String idempotencyKey);
}
