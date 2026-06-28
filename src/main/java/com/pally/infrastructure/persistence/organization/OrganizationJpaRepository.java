package com.pally.infrastructure.persistence.organization;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface OrganizationJpaRepository
        extends JpaRepository<OrganizationJpaEntity, String> {

    List<OrganizationJpaEntity> findByOwnerUserId(String ownerUserId);

    boolean existsByOwnerUserId(String ownerUserId);

    /** Returns the first org owned by this user, or empty if none. */
    @Query("SELECT o FROM OrganizationJpaEntity o WHERE o.ownerUserId = :ownerUserId ORDER BY o.createdAt ASC")
    Optional<OrganizationJpaEntity> findFirstByOwnerUserId(@Param("ownerUserId") String ownerUserId);

    /**
     * Finds organisations whose pilot has expired and are past the purge deadline.
     * Used by {@code PilotPurgeScheduler} to revoke memberships for lapsed pilots.
     */
    List<OrganizationJpaEntity> findBySubStatusAndPilotEndsAtBefore(String subStatus, Instant before);
}
