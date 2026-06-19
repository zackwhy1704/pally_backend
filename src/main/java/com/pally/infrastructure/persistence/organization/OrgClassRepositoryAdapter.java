package com.pally.infrastructure.persistence.organization;

import com.pally.domain.centre.OrgClassRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * JPA adapter implementing the {@link OrgClassRepository} domain port.
 * Provides class → organization lookup without leaking JPA into the domain.
 */
@Component
@RequiredArgsConstructor
public class OrgClassRepositoryAdapter implements OrgClassRepository {

    private final OrgClassJpaRepository jpa;

    @Override
    public Optional<String> findOrganizationIdByClassId(String classId) {
        return jpa.findById(classId).map(OrgClassJpaEntity::getOrganizationId);
    }

    @Override
    public Optional<String> findCorpusAvatarIdByClassId(String classId) {
        return jpa.findById(classId).map(OrgClassJpaEntity::getCorpusAvatarId);
    }
}
