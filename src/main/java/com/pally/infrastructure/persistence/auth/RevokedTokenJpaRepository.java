package com.pally.infrastructure.persistence.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public interface RevokedTokenJpaRepository extends JpaRepository<RevokedTokenJpaEntity, String> {

    /// Prunes expired rows that are no longer needed (JWT expiry check already
    /// blocks those tokens). Run periodically to keep the table small.
    @Modifying
    @Query(value = "DELETE FROM revoked_tokens WHERE expires_at < :now", nativeQuery = true)
    int pruneExpired(@Param("now") Instant now);
}
