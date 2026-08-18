package com.pally.infrastructure.persistence.syllabus;

import com.pally.domain.syllabus.SyllabusContentPackAlias;
import com.pally.domain.syllabus.SyllabusContentPackAliasRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Maps between the {@link SyllabusContentPackAlias} domain type and its JPA row. JPA
 * entities never leave this class.
 */
@Component
@RequiredArgsConstructor
public class SyllabusContentPackAliasRepositoryAdapter implements SyllabusContentPackAliasRepository {

    private final SyllabusContentPackAliasJpaRepository jpa;

    @Override
    @Transactional
    public SyllabusContentPackAlias save(SyllabusContentPackAlias alias) {
        return toDomain(jpa.save(toEntity(alias)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SyllabusContentPackAlias> findBySyllabusCode(String syllabusCode) {
        return jpa.findBySyllabusCode(syllabusCode).stream().map(this::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<SyllabusContentPackAlias> findByPackId(String packId) {
        return jpa.findByPackId(packId).stream().map(this::toDomain).toList();
    }

    private SyllabusContentPackAliasJpaEntity toEntity(SyllabusContentPackAlias a) {
        SyllabusContentPackAliasJpaEntity e = new SyllabusContentPackAliasJpaEntity();
        e.setId(a.id());
        e.setPackId(a.packId());
        e.setSyllabusCode(a.syllabusCode());
        e.setTopicTag(a.topicTag());
        e.setCreatedAt(a.createdAt());
        return e;
    }

    private SyllabusContentPackAlias toDomain(SyllabusContentPackAliasJpaEntity e) {
        return new SyllabusContentPackAlias(e.getId(), e.getPackId(), e.getSyllabusCode(),
                e.getTopicTag(), e.getCreatedAt());
    }
}
