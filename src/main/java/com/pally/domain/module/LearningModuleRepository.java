package com.pally.domain.module;

import java.util.List;
import java.util.Optional;

/**
 * Domain port for {@link LearningModule} persistence.
 * The JPA adapter lives in {@code infrastructure/persistence/module}.
 */
public interface LearningModuleRepository {

    LearningModule save(LearningModule module);

    Optional<LearningModule> findById(String id);

    List<LearningModule> findByAvatarId(String avatarId);

    Optional<LearningModule> findByAvatarIdAndWikiPageSlug(String avatarId, String slug);

    List<LearningModule> findByClassId(String classId);
}
