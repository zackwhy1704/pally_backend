package com.pally.infrastructure.persistence.quiz;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface FlashcardJpaRepository extends JpaRepository<FlashcardJpaEntity, String> {

    List<FlashcardJpaEntity> findByAvatarId(String avatarId);

    @Query("SELECT f FROM FlashcardJpaEntity f WHERE f.avatarId = :avatarId " +
           "AND (f.nextReviewAt IS NULL OR f.nextReviewAt <= :now)")
    List<FlashcardJpaEntity> findDueByAvatarId(
            @Param("avatarId") String avatarId,
            @Param("now") Instant now
    );
}
