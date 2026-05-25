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
}
