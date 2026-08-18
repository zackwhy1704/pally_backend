package com.pally.infrastructure.persistence.syllabus;

import com.pally.domain.syllabus.SyllabusContentPack;
import com.pally.domain.syllabus.SyllabusContentPackRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Maps between the {@link SyllabusContentPack} domain type and its JPA row. JPA
 * entities never leave this class.
 */
@Component
@RequiredArgsConstructor
public class SyllabusContentPackRepositoryAdapter implements SyllabusContentPackRepository {

    private final SyllabusContentPackJpaRepository jpa;

    @Override
    @Transactional(readOnly = true)
    public Optional<SyllabusContentPack> findBySyllabusCodeAndTopicTag(String syllabusCode, String topicTag) {
        return jpa.findBySyllabusCodeAndTopicTag(syllabusCode, topicTag).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SyllabusContentPack> findById(String id) {
        return jpa.findById(id).map(this::toDomain);
    }

    @Override
    @Transactional
    public SyllabusContentPack save(SyllabusContentPack pack) {
        return toDomain(jpa.save(toEntity(pack)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SyllabusContentPack> findByPackStatus(String packStatus) {
        return jpa.findByPackStatus(packStatus).stream().map(this::toDomain).toList();
    }

    private SyllabusContentPackJpaEntity toEntity(SyllabusContentPack p) {
        SyllabusContentPackJpaEntity e = new SyllabusContentPackJpaEntity();
        e.setId(p.id());
        e.setSyllabusCode(p.syllabusCode());
        e.setTopicTag(p.topicTag());
        e.setAvatarId(p.avatarId());
        e.setPackStatus(p.packStatus() != null ? p.packStatus() : "DRAFT");
        e.setSourceLicenseNote(p.sourceLicenseNote());
        e.setCreatedAt(p.createdAt());
        return e;
    }

    private SyllabusContentPack toDomain(SyllabusContentPackJpaEntity e) {
        return new SyllabusContentPack(e.getId(), e.getSyllabusCode(), e.getTopicTag(),
                e.getAvatarId(), e.getPackStatus(), e.getSourceLicenseNote(), e.getCreatedAt());
    }
}
