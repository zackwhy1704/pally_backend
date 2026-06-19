package com.pally.domain.module;

import java.util.List;
import java.util.Optional;

/**
 * Domain port for {@link ModuleContentItem} persistence.
 * The JPA adapter lives in {@code infrastructure/persistence/module}.
 */
public interface ModuleContentItemRepository {

    ModuleContentItem save(ModuleContentItem item);

    List<ModuleContentItem> saveAll(List<ModuleContentItem> items);

    Optional<ModuleContentItem> findById(String id);

    List<ModuleContentItem> findByModuleIdOrderBySortOrder(String moduleId);

    List<ModuleContentItem> findByModuleIdAndStageOrderBySortOrder(String moduleId, String stage);

    int countByModuleIdAndStage(String moduleId, String stage);

    /**
     * Deletes all content items belonging to the given module.
     * Used by the centre regenerate flow to clear stale draft items before
     * re-generating with teacher guidance.
     */
    void deleteByModuleId(String moduleId);
}
