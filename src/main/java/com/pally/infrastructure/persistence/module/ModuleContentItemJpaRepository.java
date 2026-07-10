package com.pally.infrastructure.persistence.module;

import org.springframework.data.jpa.repository.JpaRepository;

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
}
