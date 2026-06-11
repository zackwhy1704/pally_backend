package com.pally.infrastructure.persistence.module;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ModuleContentItemJpaRepository extends JpaRepository<ModuleContentItemJpaEntity, String> {

    List<ModuleContentItemJpaEntity> findByModuleIdOrderBySortOrder(String moduleId);

    List<ModuleContentItemJpaEntity> findByModuleIdAndStageOrderBySortOrder(String moduleId, String stage);

    int countByModuleIdAndStage(String moduleId, String stage);
}
