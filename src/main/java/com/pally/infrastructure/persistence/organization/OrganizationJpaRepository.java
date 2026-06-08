package com.pally.infrastructure.persistence.organization;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrganizationJpaRepository
        extends JpaRepository<OrganizationJpaEntity, String> {

    List<OrganizationJpaEntity> findByOwnerUserId(String ownerUserId);

    /** Returns the first org owned by this user, or empty if none. */
    @Query("SELECT o FROM OrganizationJpaEntity o WHERE o.ownerUserId = :ownerUserId ORDER BY o.createdAt ASC")
    Optional<OrganizationJpaEntity> findFirstByOwnerUserId(@Param("ownerUserId") String ownerUserId);
}
