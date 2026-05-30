package com.pally.infrastructure.persistence.progress;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Set;

@Repository
public interface StudyPlanCompletionJpaRepository
        extends JpaRepository<StudyPlanCompletionJpaEntity, StudyPlanCompletionJpaEntity.PK> {

    @Query(value = """
            SELECT task_key FROM study_plan_completions
            WHERE user_id = :userId
              AND completed_at >= :dayStart
              AND completed_at < :dayEnd
            """, nativeQuery = true)
    Set<String> findCompletedKeysToday(
            @Param("userId") String userId,
            @Param("dayStart") Instant dayStart,
            @Param("dayEnd") Instant dayEnd);
}
