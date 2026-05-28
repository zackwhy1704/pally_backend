package com.pally.infrastructure.persistence.flag;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserFeatureFlagJpaRepository
        extends JpaRepository<UserFeatureFlagJpaEntity, UserFeatureFlagJpaEntity.PK> {

    List<UserFeatureFlagJpaEntity> findByUserId(String userId);

    Optional<UserFeatureFlagJpaEntity> findByUserIdAndFlagName(
            String userId, String flagName);

    void deleteByUserIdAndFlagName(String userId, String flagName);
}
