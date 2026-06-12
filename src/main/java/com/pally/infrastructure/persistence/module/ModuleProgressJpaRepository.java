package com.pally.infrastructure.persistence.module;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ModuleProgressJpaRepository extends JpaRepository<ModuleProgressJpaEntity, String> {

    List<ModuleProgressJpaEntity> findByModuleIdAndUserId(String moduleId, String userId);

    Optional<ModuleProgressJpaEntity> findByModuleIdAndUserIdAndItemId(String moduleId, String userId, String itemId);

    int countByModuleIdAndUserIdAndStage(String moduleId, String userId, String stage);

    /**
     * Number of distinct students who have reached a given stage of a module —
     * used as the "completers" denominator for the muddiest-point threshold.
     */
    @Query("SELECT COUNT(DISTINCT p.userId) FROM ModuleProgressJpaEntity p "
            + "WHERE p.moduleId = :moduleId AND p.stage = :stage")
    long countDistinctCompletersByStage(@Param("moduleId") String moduleId,
                                        @Param("stage") String stage);
}
