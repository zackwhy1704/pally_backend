package com.pally.infrastructure.persistence.organization;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrgClassJpaRepository extends JpaRepository<OrgClassJpaEntity, String> {

    List<OrgClassJpaEntity> findByOrganizationId(String organizationId);

    Optional<OrgClassJpaEntity> findByJoinCode(String joinCode);
}
