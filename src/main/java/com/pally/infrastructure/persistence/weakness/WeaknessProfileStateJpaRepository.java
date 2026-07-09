package com.pally.infrastructure.persistence.weakness;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface WeaknessProfileStateJpaRepository
        extends JpaRepository<WeaknessProfileStateJpaEntity, String> {

    Optional<WeaknessProfileStateJpaEntity> findByUserIdAndSubject(String userId, String subject);

    /// ACCOUNT DELETION Phase 1 orphan DELETE: per-(user,subject) weakness state keyed
    /// by user_id with no FK — clear on purge.
    @Modifying
    @Query("DELETE FROM WeaknessProfileStateJpaEntity w WHERE w.userId = :userId")
    int deleteByUserId(@Param("userId") String userId);
}
