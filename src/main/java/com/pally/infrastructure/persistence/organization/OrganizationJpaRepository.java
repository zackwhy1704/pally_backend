package com.pally.infrastructure.persistence.organization;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrganizationJpaRepository
        extends JpaRepository<OrganizationJpaEntity, String> {

    List<OrganizationJpaEntity> findByOwnerUserId(String ownerUserId);
}
