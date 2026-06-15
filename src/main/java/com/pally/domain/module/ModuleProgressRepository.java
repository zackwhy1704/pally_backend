package com.pally.domain.module;

import java.util.List;
import java.util.Optional;

/**
 * Domain port for {@link ModuleProgress} persistence.
 * The JPA adapter lives in {@code infrastructure/persistence/module}.
 */
public interface ModuleProgressRepository {

    ModuleProgress save(ModuleProgress progress);

    List<ModuleProgress> findByModuleIdAndUserId(String moduleId, String userId);

    Optional<ModuleProgress> findByModuleIdAndUserIdAndItemId(
            String moduleId, String userId, String itemId);

    int countByModuleIdAndUserIdAndStage(String moduleId, String userId, String stage);

    long countDistinctCompletersByStage(String moduleId, String stage);
}
