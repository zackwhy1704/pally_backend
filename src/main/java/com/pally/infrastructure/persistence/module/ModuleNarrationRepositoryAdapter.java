package com.pally.infrastructure.persistence.module;

import com.pally.domain.module.ModuleNarration;
import com.pally.domain.module.ModuleNarrationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * JPA adapter implementing the {@link ModuleNarrationRepository} domain port.
 * Maps between {@link ModuleNarrationJpaEntity} (infrastructure) and
 * {@link ModuleNarration} (domain).
 */
@Component
@RequiredArgsConstructor
public class ModuleNarrationRepositoryAdapter implements ModuleNarrationRepository {

    private final ModuleNarrationJpaRepository jpa;

    @Override
    public ModuleNarration save(ModuleNarration narration) {
        return toDomain(jpa.save(toEntity(narration)));
    }

    @Override
    public Optional<ModuleNarration> findById(String id) {
        return jpa.findById(id).map(this::toDomain);
    }

    @Override
    public Optional<ModuleNarration> findByModuleId(String moduleId) {
        return jpa.findByModuleId(moduleId).map(this::toDomain);
    }

    @Override
    public void delete(ModuleNarration narration) {
        jpa.delete(toEntity(narration));
    }

    // ── Mapping helpers ────────────────────────────────────────────────────

    ModuleNarration toDomain(ModuleNarrationJpaEntity e) {
        ModuleNarration d = new ModuleNarration();
        d.setId(e.getId());
        d.setModuleId(e.getModuleId());
        d.setVoiceId(e.getVoiceId());
        d.setSegmentsJson(e.getSegmentsJson());
        d.setTotalDurationMs(e.getTotalDurationMs());
        d.setStatus(e.getStatus());
        d.setCreatedAt(e.getCreatedAt());
        return d;
    }

    ModuleNarrationJpaEntity toEntity(ModuleNarration d) {
        ModuleNarrationJpaEntity e = new ModuleNarrationJpaEntity();
        e.setId(d.getId());
        e.setModuleId(d.getModuleId());
        e.setVoiceId(d.getVoiceId());
        e.setSegmentsJson(d.getSegmentsJson());
        e.setTotalDurationMs(d.getTotalDurationMs());
        e.setStatus(d.getStatus());
        e.setCreatedAt(d.getCreatedAt());
        return e;
    }
}
