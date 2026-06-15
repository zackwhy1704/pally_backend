package com.pally.infrastructure.persistence.curriculum;

import com.pally.domain.curriculum.Curriculum;
import com.pally.domain.curriculum.CurriculumRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * JPA adapter implementing the {@link CurriculumRepository} domain port.
 * Maps between {@link CurriculumJpaEntity} (infrastructure) and
 * {@link Curriculum} (domain).
 */
@Component
@RequiredArgsConstructor
public class CurriculumRepositoryAdapter implements CurriculumRepository {

    private final CurriculumJpaRepository jpa;

    @Override
    @Transactional(readOnly = true)
    public List<Curriculum> findVisibleToUser(String userId, String subject) {
        return jpa.findVisibleToUser(userId, subject).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Curriculum> findById(String id) {
        return jpa.findById(id).map(this::toDomain);
    }

    @Override
    @Transactional
    public Curriculum save(Curriculum curriculum) {
        return toDomain(jpa.save(toEntity(curriculum)));
    }

    // ── Mapping helpers ────────────────────────────────────────────────────

    private Curriculum toDomain(CurriculumJpaEntity e) {
        return new Curriculum(
                e.getId(),
                e.getName(),
                e.getSubject(),
                e.getGrade(),
                e.getOwnerUserId(),
                e.isDefault(),
                e.getCreatedAt());
    }

    private CurriculumJpaEntity toEntity(Curriculum c) {
        CurriculumJpaEntity e = new CurriculumJpaEntity();
        e.setId(c.id());
        e.setName(c.name());
        e.setSubject(c.subject());
        e.setGrade(c.grade());
        e.setOwnerUserId(c.ownerUserId());
        e.setDefault(c.isDefault());
        e.setCreatedAt(c.createdAt());
        return e;
    }
}
