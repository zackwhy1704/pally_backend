package com.pally.infrastructure.persistence.curriculum;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CurriculumJpaRepository
        extends JpaRepository<CurriculumJpaEntity, String> {

    /// Curricula visible to a given user: shared/built-in (owner null) plus
    /// anything they uploaded themselves. Optionally narrowed by subject.
    @Query(value = """
            SELECT * FROM curricula
            WHERE (owner_user_id IS NULL OR owner_user_id = :userId)
              AND (:subject IS NULL OR subject = :subject)
            ORDER BY is_default DESC, created_at DESC
            """, nativeQuery = true)
    List<CurriculumJpaEntity> findVisibleToUser(
            @Param("userId") String userId,
            @Param("subject") String subject);
}
