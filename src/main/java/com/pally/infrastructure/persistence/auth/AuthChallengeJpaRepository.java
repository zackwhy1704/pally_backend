package com.pally.infrastructure.persistence.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AuthChallengeJpaRepository extends JpaRepository<AuthChallengeJpaEntity, String> {

    Optional<AuthChallengeJpaEntity> findByCodeHashAndPurposeAndStatus(
            String codeHash, String purpose, String status);

    List<AuthChallengeJpaEntity> findByUserIdAndPurposeAndStatus(
            String userId, String purpose, String status);

    /// ACCOUNT DELETION Phase 1 orphan DELETE: short-lived auth ephemera (reset/link/
    /// delete/restore codes) keyed by user_id with no FK — clear them on purge.
    @Modifying
    @Query("DELETE FROM AuthChallengeJpaEntity a WHERE a.userId = :userId")
    int deleteByUserId(@Param("userId") String userId);
}
