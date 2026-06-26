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
}
