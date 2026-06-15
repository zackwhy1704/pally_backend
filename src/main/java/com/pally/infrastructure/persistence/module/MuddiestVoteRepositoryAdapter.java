package com.pally.infrastructure.persistence.module;

import com.pally.domain.module.MuddiestVote;
import com.pally.domain.module.MuddiestVoteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * JPA adapter implementing the {@link MuddiestVoteRepository} domain port.
 * Maps between {@link MuddiestVoteJpaEntity} (infrastructure) and
 * {@link MuddiestVote} (domain).
 */
@Component
@RequiredArgsConstructor
public class MuddiestVoteRepositoryAdapter implements MuddiestVoteRepository {

    private final MuddiestVoteJpaRepository jpa;

    @Override
    public MuddiestVote save(MuddiestVote vote) {
        return toDomain(jpa.save(toEntity(vote)));
    }

    @Override
    public Optional<MuddiestVote> findByModuleIdAndUserId(String moduleId, String userId) {
        return jpa.findByModuleIdAndUserId(moduleId, userId).map(this::toDomain);
    }

    @Override
    public long countByModuleId(String moduleId) {
        return jpa.countByModuleId(moduleId);
    }

    @Override
    public List<Object[]> countByConcept(String moduleId) {
        return jpa.countByConcept(moduleId);
    }

    // ── Mapping helpers ────────────────────────────────────────────────────

    MuddiestVote toDomain(MuddiestVoteJpaEntity e) {
        MuddiestVote d = new MuddiestVote();
        d.setId(e.getId());
        d.setModuleId(e.getModuleId());
        d.setClassId(e.getClassId());
        d.setConceptId(e.getConceptId());
        d.setUserId(e.getUserId());
        d.setCreatedAt(e.getCreatedAt());
        return d;
    }

    MuddiestVoteJpaEntity toEntity(MuddiestVote d) {
        MuddiestVoteJpaEntity e = new MuddiestVoteJpaEntity();
        e.setId(d.getId());
        e.setModuleId(d.getModuleId());
        e.setClassId(d.getClassId());
        e.setConceptId(d.getConceptId());
        e.setUserId(d.getUserId());
        e.setCreatedAt(d.getCreatedAt());
        return e;
    }
}
