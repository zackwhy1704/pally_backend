package com.pally.infrastructure.persistence.module;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ModuleProgressJpaRepository extends JpaRepository<ModuleProgressJpaEntity, String> {

    List<ModuleProgressJpaEntity> findByModuleIdAndUserId(String moduleId, String userId);

    Optional<ModuleProgressJpaEntity> findByModuleIdAndUserIdAndItemId(String moduleId, String userId, String itemId);

    int countByModuleIdAndUserIdAndStage(String moduleId, String userId, String stage);
}
