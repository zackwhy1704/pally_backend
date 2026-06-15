package com.pally.infrastructure.persistence.curriculum;

import com.pally.domain.curriculum.CurriculumTopic;
import com.pally.domain.curriculum.CurriculumTopicRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * JPA adapter implementing the {@link CurriculumTopicRepository} domain port.
 * Maps between {@link CurriculumTopicJpaEntity} (infrastructure) and
 * {@link CurriculumTopic} (domain).
 */
@Component
@RequiredArgsConstructor
public class CurriculumTopicRepositoryAdapter implements CurriculumTopicRepository {

    private final CurriculumTopicJpaRepository jpa;

    @Override
    @Transactional(readOnly = true)
    public List<CurriculumTopic> findByCurriculumIdOrderBySequenceAsc(String curriculumId) {
        return jpa.findByCurriculumIdOrderBySequenceAsc(curriculumId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public CurriculumTopic save(CurriculumTopic topic) {
        return toDomain(jpa.save(toEntity(topic)));
    }

    // ── Mapping helpers ────────────────────────────────────────────────────

    private CurriculumTopic toDomain(CurriculumTopicJpaEntity e) {
        return new CurriculumTopic(
                e.getId(),
                e.getCurriculumId(),
                e.getName(),
                e.getSlug(),
                e.getSequence(),
                e.getCreatedAt());
    }

    private CurriculumTopicJpaEntity toEntity(CurriculumTopic t) {
        CurriculumTopicJpaEntity e = new CurriculumTopicJpaEntity();
        e.setId(t.id());
        e.setCurriculumId(t.curriculumId());
        e.setName(t.name());
        e.setSlug(t.slug());
        e.setSequence(t.sequence());
        e.setCreatedAt(t.createdAt());
        return e;
    }
}
