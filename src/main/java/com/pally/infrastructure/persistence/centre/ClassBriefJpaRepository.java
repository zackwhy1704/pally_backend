package com.pally.infrastructure.persistence.centre;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ClassBriefJpaRepository extends JpaRepository<ClassBriefJpaEntity, String> {

    @Query("SELECT b FROM ClassBriefJpaEntity b WHERE b.classId = :classId "
            + "AND ((:moduleId IS NULL AND b.moduleId IS NULL) OR b.moduleId = :moduleId)")
    Optional<ClassBriefJpaEntity> findByClassIdAndModuleId(
            @Param("classId") String classId,
            @Param("moduleId") String moduleId);

    @Modifying
    @Query("DELETE FROM ClassBriefJpaEntity b WHERE b.classId = :classId "
            + "AND ((:moduleId IS NULL AND b.moduleId IS NULL) OR b.moduleId = :moduleId)")
    void deleteByClassIdAndModuleId(
            @Param("classId") String classId,
            @Param("moduleId") String moduleId);

    /**
     * Max completed_at across all module_progress rows for students in this class,
     * optionally filtered to a single module. Used to detect stale cached briefs.
     */
    @Query(value = """
            SELECT MAX(mp.completed_at)
            FROM module_progress mp
            JOIN class_membership cm ON cm.user_id = mp.user_id
            WHERE cm.class_id = :classId
              AND cm.status = 'ACTIVE'
              AND (:moduleId IS NULL OR mp.module_id = :moduleId)
            """, nativeQuery = true)
    Optional<Instant> findMaxProgressCompletedAt(
            @Param("classId") String classId,
            @Param("moduleId") String moduleId);

    /**
     * All active student user IDs in a class — used by ClassBriefService to scope
     * progress queries without an extra repository dependency.
     */
    @Query(value = """
            SELECT cm.user_id
            FROM class_membership cm
            WHERE cm.class_id = :classId AND cm.status = 'ACTIVE'
            """, nativeQuery = true)
    List<String> findActiveStudentIds(@Param("classId") String classId);
}
