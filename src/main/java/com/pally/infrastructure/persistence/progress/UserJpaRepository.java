package com.pally.infrastructure.persistence.progress;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserJpaRepository extends JpaRepository<UserJpaEntity, String> {
    Optional<UserJpaEntity> findByEmail(String email);
    boolean existsByEmail(String email);

    Optional<UserJpaEntity> findByLinkCode(String linkCode);

    List<UserJpaEntity> findByParentId(String parentId);

    Optional<UserJpaEntity> findByReferralCode(String referralCode);

    List<UserJpaEntity> findByCentreId(String centreId);

    List<UserJpaEntity> findByCentreIdAndCohortLabel(
            String centreId, String cohortLabel);

    long countByCentreId(String centreId);

    long countByCentreIdAndCohortLabel(String centreId, String cohortLabel);

    Page<UserJpaEntity> findByCentreId(String centreId, Pageable pageable);

    Page<UserJpaEntity> findByCentreIdAndCohortLabel(
            String centreId, String cohortLabel, Pageable pageable);
}
