package com.pally.infrastructure.persistence.module;

import com.pally.domain.module.LearningModule;
import com.pally.domain.module.LearningModuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * JPA adapter implementing the {@link LearningModuleRepository} domain port.
 * Maps between {@link LearningModuleJpaEntity} (infrastructure) and
 * {@link LearningModule} (domain).
 */
@Component
@RequiredArgsConstructor
public class LearningModuleRepositoryAdapter implements LearningModuleRepository {

    private final LearningModuleJpaRepository jpa;

    @Override
    public LearningModule save(LearningModule module) {
        return toDomain(jpa.save(toEntity(module)));
    }

    @Override
    public Optional<LearningModule> findById(String id) {
        return jpa.findById(id).map(this::toDomain);
    }

    @Override
    public List<LearningModule> findByAvatarId(String avatarId) {
        return jpa.findByAvatarId(avatarId).stream().map(this::toDomain).toList();
    }

    @Override
    public Optional<LearningModule> findByAvatarIdAndWikiPageSlug(String avatarId, String slug) {
        return jpa.findByAvatarIdAndWikiPageSlug(avatarId, slug).map(this::toDomain);
    }

    @Override
    public List<LearningModule> findByClassId(String classId) {
        return jpa.findByClassId(classId).stream().map(this::toDomain).toList();
    }

    // ── Mapping helpers ────────────────────────────────────────────────────

    LearningModule toDomain(LearningModuleJpaEntity e) {
        LearningModule m = new LearningModule();
        m.setId(e.getId());
        m.setAvatarId(e.getAvatarId());
        m.setClassId(e.getClassId());
        m.setWikiPageSlug(e.getWikiPageSlug());
        m.setTitle(e.getTitle());
        m.setStage(e.getStage());
        m.setTier(e.getTier());
        m.setMasteryPct(e.getMasteryPct());
        m.setCreatedAt(e.getCreatedAt());
        m.setCompletedAt(e.getCompletedAt());
        m.setContentLanguage(e.getContentLanguage() != null ? e.getContentLanguage() : "en");
        return m;
    }

    LearningModuleJpaEntity toEntity(LearningModule m) {
        LearningModuleJpaEntity e = new LearningModuleJpaEntity();
        e.setId(m.getId());
        e.setAvatarId(m.getAvatarId());
        e.setClassId(m.getClassId());
        e.setWikiPageSlug(m.getWikiPageSlug());
        e.setTitle(m.getTitle());
        e.setStage(m.getStage());
        e.setTier(m.getTier());
        e.setMasteryPct(m.getMasteryPct());
        e.setCreatedAt(m.getCreatedAt());
        e.setCompletedAt(m.getCompletedAt());
        e.setContentLanguage(m.getContentLanguage());
        return e;
    }
}
