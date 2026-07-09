package com.pally.infrastructure.persistence.quiz;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface QuizSubmissionIdempotencyJpaRepository
        extends JpaRepository<QuizSubmissionIdempotencyJpaEntity, String> {

    Optional<QuizSubmissionIdempotencyJpaEntity> findByUserIdAndIdempotencyKey(
            String userId, String idempotencyKey);

    /// ACCOUNT DELETION Phase 1 orphan DELETE: idempotency guard rows keyed by user_id
    /// with no FK — clear on purge.
    @Modifying
    @Query("DELETE FROM QuizSubmissionIdempotencyJpaEntity q WHERE q.userId = :userId")
    int deleteByUserId(@Param("userId") String userId);
}
