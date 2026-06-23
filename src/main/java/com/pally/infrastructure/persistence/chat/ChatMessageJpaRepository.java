package com.pally.infrastructure.persistence.chat;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ChatMessageJpaRepository extends JpaRepository<ChatMessageJpaEntity, String> {

    List<ChatMessageJpaEntity> findByAvatarIdOrderByCreatedAtDescRoleAsc(String avatarId, Pageable pageable);

    /// Ownership check for the feedback IDOR guard: true only when this message
    /// belongs to the given user.
    boolean existsByIdAndUserId(String id, String userId);

    List<ChatMessageJpaEntity> findByAvatarIdAndCreatedAtAfterOrderByCreatedAtAscRoleDesc(
            String avatarId, Instant since);

    long countByAvatarId(String avatarId);

    long countByModelUsedAndCreatedAtAfter(String modelUsed, Instant since);

    // ── Cache-effectiveness aggregates (for admin cost endpoint) ─────────────

    @Query("SELECT COUNT(m) FROM ChatMessageJpaEntity m WHERE m.cacheHit = true AND m.createdAt > :since")
    long countCacheHitsAfter(@Param("since") Instant since);

    @Query("SELECT COUNT(m) FROM ChatMessageJpaEntity m WHERE m.cacheHit IS NOT NULL AND m.createdAt > :since")
    long countCacheMissesAfter(@Param("since") Instant since);

    @Query("SELECT COALESCE(SUM(m.cacheReadTokens), 0) FROM ChatMessageJpaEntity m WHERE m.createdAt > :since")
    long sumCacheReadTokensAfter(@Param("since") Instant since);

    @Query("SELECT COALESCE(SUM(m.cacheWriteTokens), 0) FROM ChatMessageJpaEntity m WHERE m.createdAt > :since")
    long sumCacheWriteTokensAfter(@Param("since") Instant since);

    @Query("SELECT COALESCE(SUM(m.totalInputTokens), 0) FROM ChatMessageJpaEntity m WHERE m.createdAt > :since")
    long sumTotalInputTokensAfter(@Param("since") Instant since);

    @Query("SELECT COALESCE(SUM(m.totalOutputTokens), 0) FROM ChatMessageJpaEntity m WHERE m.createdAt > :since")
    long sumTotalOutputTokensAfter(@Param("since") Instant since);

    @Modifying
    @Query("UPDATE ChatMessageJpaEntity m SET m.modelUsed = :modelUsed WHERE m.id = :id")
    void updateModelUsed(@Param("id") String id, @Param("modelUsed") String modelUsed);

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
