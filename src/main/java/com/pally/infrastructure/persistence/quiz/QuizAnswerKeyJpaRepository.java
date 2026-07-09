package com.pally.infrastructure.persistence.quiz;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface QuizAnswerKeyJpaRepository
        extends JpaRepository<QuizAnswerKeyJpaEntity, String> {

    List<QuizAnswerKeyJpaEntity> findByQuestionIdIn(Collection<String> questionIds);

    /// Bulk delete (not load-then-delete) so the daily reaper stays cheap even
    /// when millions of stale rows have accumulated. Uses the created_at index.
    @Modifying
    @Query("DELETE FROM QuizAnswerKeyJpaEntity k WHERE k.createdAt < :cutoff")
    int deleteByCreatedAtBefore(@Param("cutoff") Instant cutoff);

    /// ACCOUNT DELETION Phase 1 orphan DELETE: answer keys are keyed by avatar_id with
    /// no FK, so they don't cascade when the avatar row is deleted — clear them here.
    @Modifying
    @Query("DELETE FROM QuizAnswerKeyJpaEntity k WHERE k.avatarId = :avatarId")
    int deleteByAvatarId(@Param("avatarId") String avatarId);
}
