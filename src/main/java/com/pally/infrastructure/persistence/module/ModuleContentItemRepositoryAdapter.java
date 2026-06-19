package com.pally.infrastructure.persistence.module;

import com.pally.domain.module.ModuleContentItem;
import com.pally.domain.module.ModuleContentItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * JPA adapter implementing the {@link ModuleContentItemRepository} domain port.
 * Maps between {@link ModuleContentItemJpaEntity} (infrastructure) and
 * {@link ModuleContentItem} (domain).
 */
@Component
@RequiredArgsConstructor
public class ModuleContentItemRepositoryAdapter implements ModuleContentItemRepository {

    private final ModuleContentItemJpaRepository jpa;

    @Override
    public ModuleContentItem save(ModuleContentItem item) {
        return toDomain(jpa.save(toEntity(item)));
    }

    @Override
    public List<ModuleContentItem> saveAll(List<ModuleContentItem> items) {
        List<ModuleContentItemJpaEntity> entities = items.stream().map(this::toEntity).toList();
        return jpa.saveAll(entities).stream().map(this::toDomain).toList();
    }

    @Override
    public Optional<ModuleContentItem> findById(String id) {
        return jpa.findById(id).map(this::toDomain);
    }

    @Override
    public List<ModuleContentItem> findByModuleIdOrderBySortOrder(String moduleId) {
        return jpa.findByModuleIdOrderBySortOrder(moduleId).stream().map(this::toDomain).toList();
    }

    @Override
    public List<ModuleContentItem> findByModuleIdAndStageOrderBySortOrder(
            String moduleId, String stage) {
        return jpa.findByModuleIdAndStageOrderBySortOrder(moduleId, stage)
                .stream().map(this::toDomain).toList();
    }

    @Override
    public int countByModuleIdAndStage(String moduleId, String stage) {
        return jpa.countByModuleIdAndStage(moduleId, stage);
    }

    @Override
    @Transactional
    public void deleteByModuleId(String moduleId) {
        jpa.deleteByModuleId(moduleId);
    }

    // ── Mapping helpers ────────────────────────────────────────────────────

    ModuleContentItem toDomain(ModuleContentItemJpaEntity e) {
        ModuleContentItem d = new ModuleContentItem();
        d.setId(e.getId());
        d.setModuleId(e.getModuleId());
        d.setStage(e.getStage());
        d.setType(e.getType());
        d.setContentJson(e.getContentJson());
        d.setAnswerJson(e.getAnswerJson());
        d.setSortOrder(e.getSortOrder());
        d.setTierRequired(e.getTierRequired());
        d.setCreatedAt(e.getCreatedAt());
        d.setStatus(e.getStatus());
        return d;
    }

    ModuleContentItemJpaEntity toEntity(ModuleContentItem d) {
        ModuleContentItemJpaEntity e = new ModuleContentItemJpaEntity();
        e.setId(d.getId());
        e.setModuleId(d.getModuleId());
        e.setStage(d.getStage());
        e.setType(d.getType());
        e.setContentJson(d.getContentJson());
        e.setAnswerJson(d.getAnswerJson());
        e.setSortOrder(d.getSortOrder());
        e.setTierRequired(d.getTierRequired());
        e.setCreatedAt(d.getCreatedAt());
        e.setStatus(d.getStatus());
        return e;
    }
}
