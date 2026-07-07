package com.pally.infrastructure.persistence.module;

import com.pally.domain.module.ModuleProgress;
import com.pally.domain.module.ModuleProgressRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * JPA adapter implementing the {@link ModuleProgressRepository} domain port.
 * Maps between {@link ModuleProgressJpaEntity} (infrastructure) and
 * {@link ModuleProgress} (domain).
 */
@Component
@RequiredArgsConstructor
public class ModuleProgressRepositoryAdapter implements ModuleProgressRepository {

    private final ModuleProgressJpaRepository jpa;

    @Override
    public ModuleProgress save(ModuleProgress progress) {
        return toDomain(jpa.save(toEntity(progress)));
    }

    @Override
    public List<ModuleProgress> findByModuleIdAndUserId(String moduleId, String userId) {
        return jpa.findByModuleIdAndUserId(moduleId, userId).stream().map(this::toDomain).toList();
    }

    @Override
    public Optional<ModuleProgress> findByModuleIdAndUserIdAndItemId(
            String moduleId, String userId, String itemId) {
        return jpa.findByModuleIdAndUserIdAndItemId(moduleId, userId, itemId).map(this::toDomain);
    }

    @Override
    public int countByModuleIdAndUserIdAndStage(String moduleId, String userId, String stage) {
        return jpa.countByModuleIdAndUserIdAndStage(moduleId, userId, stage);
    }

    @Override
    public long countDistinctCompletersByStage(String moduleId, String stage) {
        return jpa.countDistinctCompletersByStage(moduleId, stage);
    }

    // ── Mapping helpers ────────────────────────────────────────────────────

    ModuleProgress toDomain(ModuleProgressJpaEntity e) {
        ModuleProgress d = new ModuleProgress();
        d.setId(e.getId());
        d.setModuleId(e.getModuleId());
        d.setUserId(e.getUserId());
        d.setItemId(e.getItemId());
        d.setStage(e.getStage());
        d.setResponseJson(e.getResponseJson());
        d.setScore(e.getScore());
        d.setTargetConcept(e.getTargetConcept());
        d.setCompletedAt(e.getCompletedAt());
        d.setSignalType(e.getSignalType());
        return d;
    }

    ModuleProgressJpaEntity toEntity(ModuleProgress d) {
        ModuleProgressJpaEntity e = new ModuleProgressJpaEntity();
        e.setId(d.getId());
        e.setModuleId(d.getModuleId());
        e.setUserId(d.getUserId());
        e.setItemId(d.getItemId());
        e.setStage(d.getStage());
        e.setResponseJson(d.getResponseJson());
        e.setScore(d.getScore());
        e.setTargetConcept(d.getTargetConcept());
        e.setCompletedAt(d.getCompletedAt());
        e.setSignalType(d.getSignalType());
        return e;
    }
}
