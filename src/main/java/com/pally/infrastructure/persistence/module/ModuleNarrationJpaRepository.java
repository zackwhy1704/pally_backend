package com.pally.infrastructure.persistence.module;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ModuleNarrationJpaRepository extends JpaRepository<ModuleNarrationJpaEntity, String> {

    Optional<ModuleNarrationJpaEntity> findByModuleId(String moduleId);
}
