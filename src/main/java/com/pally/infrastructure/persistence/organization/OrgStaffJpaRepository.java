package com.pally.infrastructure.persistence.organization;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrgStaffJpaRepository extends JpaRepository<OrgStaffJpaEntity, String> {
    Optional<OrgStaffJpaEntity> findByOrgIdAndUserId(String orgId, String userId);
    List<OrgStaffJpaEntity> findByOrgIdAndStatus(String orgId, String status);
    boolean existsByOrgIdAndUserIdAndStatus(String orgId, String userId, String status);
    boolean existsByUserIdAndStatus(String userId, String status);
    Optional<OrgStaffJpaEntity> findFirstByUserIdAndStatus(String userId, String status);
}
