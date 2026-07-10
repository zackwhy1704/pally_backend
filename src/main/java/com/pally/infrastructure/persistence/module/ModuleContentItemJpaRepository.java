package com.pally.infrastructure.persistence.module;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface ModuleContentItemJpaRepository extends JpaRepository<ModuleContentItemJpaEntity, String> {

    List<ModuleContentItemJpaEntity> findByModuleIdOrderBySortOrder(String moduleId);

    List<ModuleContentItemJpaEntity> findByModuleIdAndStageOrderBySortOrder(String moduleId, String stage);

    /// Servable-status-filtered reads (see ModuleContentItemRepository.SERVABLE_STATUSES).
    List<ModuleContentItemJpaEntity> findByModuleIdAndStatusInOrderBySortOrder(
            String moduleId, java.util.Collection<String> statuses);

    List<ModuleContentItemJpaEntity> findByModuleIdAndStageAndStatusInOrderBySortOrder(
            String moduleId, String stage, java.util.Collection<String> statuses);

    int countByModuleIdAndStage(String moduleId, String stage);

    void deleteByModuleId(String moduleId);

    /// Content-health reaper scan cursor: servable items not scanned since the cutoff,
    /// oldest-scan first (nulls = never scanned, go first).
    @Query("SELECT e FROM ModuleContentItemJpaEntity e WHERE e.status IN :statuses "
            + "AND (e.reapLastAttemptAt IS NULL OR e.reapLastAttemptAt < :cutoff) "
            + "ORDER BY e.reapLastAttemptAt ASC NULLS FIRST")
    List<ModuleContentItemJpaEntity> findReapScanCandidates(
            @Param("statuses") Collection<String> statuses,
            @Param("cutoff") Instant cutoff, Pageable pageable);

    /// Read-only paging over items in the given statuses (DRY_RUN damage report).
    List<ModuleContentItemJpaEntity> findByStatusIn(Collection<String> statuses, Pageable pageable);
}
