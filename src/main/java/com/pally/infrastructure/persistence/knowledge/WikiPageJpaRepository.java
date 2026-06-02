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

    @Query("SELECT COUNT(p) FROM WikiPageJpaEntity p WHERE p.avatarId = :avatarId AND p.status = 'ACTIVE'")
    int countActiveByAvatarId(@Param("avatarId") String avatarId);

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

    /// A3 — archive ACTIVE pages whose slug is NOT in the surviving list.
    @Modifying
    @Query("UPDATE WikiPageJpaEntity p SET p.status = 'ARCHIVED' " +
           "WHERE p.avatarId = :avatarId AND p.status = 'ACTIVE' " +
           "AND p.slug NOT IN :survivingSlugs")
    int archiveOrphanPages(@Param("avatarId") String avatarId,
                           @Param("survivingSlugs") List<String> survivingSlugs);

    /// A3 — archive ALL active pages for an avatar (when no files remain).
    @Modifying
    @Query("UPDATE WikiPageJpaEntity p SET p.status = 'ARCHIVED' " +
           "WHERE p.avatarId = :avatarId AND p.status = 'ACTIVE'")
    int archiveAllActivePages(@Param("avatarId") String avatarId);

    /// A3 — find avatarIds where the newest knowledge_file.created_at is
    /// newer than the newest wiki_page.updated_at, OR files exist but 0
    /// active wiki pages. Uses a native query for the cross-table check.
    @Query(value =
           "SELECT DISTINCT kf.avatar_id " +
           "FROM knowledge_files kf " +
           "WHERE kf.status = 'READY' " +
           "  AND ( " +
           "    (SELECT COUNT(*) FROM wiki_pages wp " +
           "     WHERE wp.avatar_id = kf.avatar_id AND wp.status = 'ACTIVE') = 0 " +
           "    OR " +
           "    kf.created_at > (SELECT MAX(wp2.updated_at) FROM wiki_pages wp2 " +
           "                     WHERE wp2.avatar_id = kf.avatar_id AND wp2.status = 'ACTIVE') " +
           "  )",
           nativeQuery = true)
    List<String> findAvatarIdsNeedingRecompile();
}
