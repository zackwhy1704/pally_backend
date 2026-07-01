package com.pally.infrastructure.persistence.marking;

import com.pally.domain.marking.MarkingCorpus;
import com.pally.domain.marking.MarkingCorpusRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Maps between the {@link MarkingCorpus} domain type and its JPA row. JPA
 * entities never leave this class.
 */
@Component
@RequiredArgsConstructor
public class MarkingCorpusRepositoryAdapter implements MarkingCorpusRepository {

    private final MarkingCorpusJpaRepository jpa;

    @Override
    @Transactional(readOnly = true)
    public Optional<MarkingCorpus> findByOrgIdAndSubject(String orgId, String subject) {
        return jpa.findByOrgIdAndSubject(orgId, subject).map(this::toDomain);
    }

    @Override
    @Transactional
    public MarkingCorpus save(MarkingCorpus corpus) {
        return toDomain(jpa.save(toEntity(corpus)));
    }

    private MarkingCorpusJpaEntity toEntity(MarkingCorpus c) {
        MarkingCorpusJpaEntity e = new MarkingCorpusJpaEntity();
        e.setId(c.id());
        e.setOrgId(c.orgId());
        e.setSubject(c.subject());
        e.setAvatarId(c.avatarId());
        e.setCreatedAt(c.createdAt());
        return e;
    }

    private MarkingCorpus toDomain(MarkingCorpusJpaEntity e) {
        return new MarkingCorpus(e.getId(), e.getOrgId(), e.getSubject(),
                e.getAvatarId(), e.getCreatedAt());
    }
}
