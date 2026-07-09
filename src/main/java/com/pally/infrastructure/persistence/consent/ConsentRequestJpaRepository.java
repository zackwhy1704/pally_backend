package com.pally.infrastructure.persistence.consent;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConsentRequestJpaRepository
        extends JpaRepository<ConsentRequestJpaEntity, String> {

    Optional<ConsentRequestJpaEntity> findByToken(String token);

    List<ConsentRequestJpaEntity> findByChildUserIdOrderByCreatedAtDesc(String childUserId);

    Optional<ConsentRequestJpaEntity> findFirstByChildUserIdAndStatusOrderByCreatedAtDesc(
            String childUserId, String status);

    /// ACCOUNT DELETION Phase 1 minimization: on purge, the consent_requests row is
    /// RETAINED as PDPC parental-consent proof, but its reusable approval token is
    /// scrubbed to a tombstone (retain the evidence, not a live secret). Tombstone
    /// keeps the NOT NULL + UNIQUE contract via the PK id.
    @Modifying
    @Query("UPDATE ConsentRequestJpaEntity c SET c.token = CONCAT('PURGED:', c.id) "
            + "WHERE c.childUserId = :userId")
    int scrubTokensByChildUserId(@Param("userId") String userId);
}
