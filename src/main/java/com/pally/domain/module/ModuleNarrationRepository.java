package com.pally.domain.module;

import java.util.Optional;

/**
 * Domain port for {@link ModuleNarration} persistence.
 * The JPA adapter lives in {@code infrastructure/persistence/module}.
 */
public interface ModuleNarrationRepository {

    ModuleNarration save(ModuleNarration narration);

    Optional<ModuleNarration> findById(String id);

    Optional<ModuleNarration> findByModuleId(String moduleId);

    void delete(ModuleNarration narration);
}
