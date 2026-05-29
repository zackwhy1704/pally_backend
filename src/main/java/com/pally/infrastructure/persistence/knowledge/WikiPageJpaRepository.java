package com.pally.infrastructure.persistence.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface WikiPageJpaRepository extends JpaRepository<WikiPageJpaEntity, String> {

    Optional<WikiPageJpaEntity> findByAvatarIdAndSlug(String avatarId, String slug);

    List<WikiPageJpaEntity> findByAvatarId(String avatarId);

    @Query("SELECT p FROM WikiPageJpaEntity p WHERE p.avatarId = :avatarId AND p.status = 'ACTIVE' ORDER BY p.updatedAt DESC")
    List<WikiPageJpaEntity> findActiveByAvatarId(@Param("avatarId") String avatarId);

    int countByAvatarId(String avatarId);

    void deleteByAvatarId(String avatarId);

    @Modifying
    @Query("UPDATE WikiPageJpaEntity p SET p.lastRetrievedAt = :now WHERE p.avatarId = :avatarId AND p.slug IN :slugs")
    int recordRetrieval(@Param("avatarId") String avatarId, @Param("slugs") List<String> slugs, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE WikiPageJpaEntity p SET p.quizUseCount = p.quizUseCount + 1 WHERE p.avatarId = :avatarId AND p.slug IN :slugs")
    int incrementQuizUseCount(@Param("avatarId") String avatarId, @Param("slugs") List<String> slugs);

    @Query("SELECT p FROM WikiPageJpaEntity p WHERE p.avatarId = :avatarId AND p.status = 'ACTIVE' AND p.reviewRequired = TRUE")
    List<WikiPageJpaEntity> findReviewRequired(@Param("avatarId") String avatarId);

    @Query("SELECT SUM(LENGTH(p.content)) FROM WikiPageJpaEntity p WHERE p.avatarId = :avatarId AND p.status = 'ACTIVE'")
    Long sumContentLengthByAvatarId(@Param("avatarId") String avatarId);

    /// Harness feedback — nudge certainty in [0.1, 1.0]. JPQL has no GREATEST/LEAST
    /// support so we clamp in the WHERE clause via CASE.
    @Modifying
    @Query("UPDATE WikiPageJpaEntity p SET p.certaintyScore = " +
           "CASE " +
           "  WHEN p.certaintyScore + :delta > 1.0 THEN 1.0 " +
           "  WHEN p.certaintyScore + :delta < 0.1 THEN 0.1 " +
           "  ELSE p.certaintyScore + :delta " +
           "END, p.updatedAt = :now " +
           "WHERE p.avatarId = :avatarId AND p.slug IN :slugs")
    int adjustCertainty(@Param("avatarId") String avatarId,
                        @Param("slugs") List<String> slugs,
                        @Param("delta") double delta,
                        @Param("now") Instant now);

    /// Toggle reviewRequired for a batch of slugs.
    @Modifying
    @Query("UPDATE WikiPageJpaEntity p SET p.reviewRequired = :value " +
           "WHERE p.avatarId = :avatarId AND p.slug IN :slugs")
    int setReviewRequired(@Param("avatarId") String avatarId,
                          @Param("slugs") List<String> slugs,
                          @Param("value") boolean value);

    /// R6 — flip ACTIVE → ARCHIVED for pages not retrieved since `cutoff`.
    /// Pages never retrieved (lastRetrievedAt IS NULL) are left alone — they
    /// were just compiled and haven't had a chance to be used yet.
    @Modifying
    @Query("UPDATE WikiPageJpaEntity p SET p.status = 'ARCHIVED' " +
           "WHERE p.avatarId = :avatarId AND p.status = 'ACTIVE' " +
           "AND p.lastRetrievedAt IS NOT NULL AND p.lastRetrievedAt < :cutoff")
    int archiveStalePages(@Param("avatarId") String avatarId,
                          @Param("cutoff") Instant cutoff);
}
