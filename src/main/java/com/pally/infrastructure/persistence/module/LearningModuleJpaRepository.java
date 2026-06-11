package com.pally.infrastructure.persistence.module;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LearningModuleJpaRepository extends JpaRepository<LearningModuleJpaEntity, String> {

    List<LearningModuleJpaEntity> findByAvatarId(String avatarId);

    Optional<LearningModuleJpaEntity> findByAvatarIdAndWikiPageSlug(String avatarId, String slug);

    List<LearningModuleJpaEntity> findByClassId(String classId);
}
