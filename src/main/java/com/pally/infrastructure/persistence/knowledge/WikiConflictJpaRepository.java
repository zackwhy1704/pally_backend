package com.pally.infrastructure.persistence.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface WikiConflictJpaRepository extends JpaRepository<WikiConflictJpaEntity, String> {

    /** Open conflicts for the teacher queue — DETERMINISTIC (exact, high-stakes) first. */
    @Query("""
            SELECT c FROM WikiConflictJpaEntity c
            WHERE c.avatarId = :avatarId AND c.status = 'OPEN'
            ORDER BY CASE WHEN c.confidence = 'DETERMINISTIC' THEN 0 ELSE 1 END, c.createdAt DESC
            """)
    List<WikiConflictJpaEntity> findOpenForQueue(@Param("avatarId") String avatarId);

    boolean existsByAvatarIdAndSlugAndStatus(String avatarId, String slug, String status);

    boolean existsByAvatarIdAndSlugAndStatusAndConfidence(
            String avatarId, String slug, String status, String confidence);
}
