package com.pally.infrastructure.persistence.chat;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ChatMessageJpaRepository extends JpaRepository<ChatMessageJpaEntity, String> {

    List<ChatMessageJpaEntity> findByAvatarIdOrderByCreatedAtDesc(String avatarId, Pageable pageable);

    List<ChatMessageJpaEntity> findByAvatarIdAndCreatedAtAfterOrderByCreatedAtAsc(
            String avatarId, Instant since);

    long countByAvatarId(String avatarId);

    @Modifying
    @Query("UPDATE ChatMessageJpaEntity m SET m.feedbackType = :feedbackType WHERE m.id = :id")
    void updateFeedbackType(@Param("id") String id, @Param("feedbackType") String feedbackType);

    @Modifying
    @Query("UPDATE ChatMessageJpaEntity m SET m.savedToBrain = true WHERE m.id = :id")
    void markSavedToBrain(@Param("id") String id);

    @Modifying
    @Query("""
            UPDATE ChatMessageJpaEntity m
            SET m.cacheHit = :cacheHit,
                m.cacheReadTokens = :cacheReadTokens,
                m.cacheWriteTokens = :cacheWriteTokens,
                m.totalInputTokens = :totalInputTokens,
                m.totalOutputTokens = :totalOutputTokens
            WHERE m.id = :id
            """)
    void updateCacheMetrics(
            @Param("id") String id,
            @Param("cacheHit") boolean cacheHit,
            @Param("cacheReadTokens") int cacheReadTokens,
            @Param("cacheWriteTokens") int cacheWriteTokens,
            @Param("totalInputTokens") int totalInputTokens,
            @Param("totalOutputTokens") int totalOutputTokens);
}
