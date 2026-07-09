package com.pally.infrastructure.persistence.organization;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrgClassJpaRepository extends JpaRepository<OrgClassJpaEntity, String> {

    List<OrgClassJpaEntity> findByOrganizationId(String organizationId);

    /// Count of classes in an org. Used by the account-deletion block-unless-empty
    /// guard (CentreAccessService.isOwnedCentreEmpty): an owner with any class may
    /// not delete until the centre is transferred or closed.
    long countByOrganizationId(String organizationId);

    Optional<OrgClassJpaEntity> findByJoinCode(String joinCode);

    /// The class whose hidden corpus avatar this is. Lets the avatar mapper
    /// resolve the Mochi config for a corpus avatar (which has classId == null).
    Optional<OrgClassJpaEntity> findByCorpusAvatarId(String corpusAvatarId);

    List<OrgClassJpaEntity> findByCorpusAvatarIdIn(java.util.Collection<String> corpusAvatarIds);
}
