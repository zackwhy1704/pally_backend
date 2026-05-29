package com.pally.infrastructure.persistence.referral;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReferralJpaRepository
        extends JpaRepository<ReferralJpaEntity, String> {

    Optional<ReferralJpaEntity> findByRefereeUserId(String refereeUserId);

    List<ReferralJpaEntity> findByReferrerUserIdOrderByCreatedAtDesc(
            String referrerUserId);

    /// Paginated variant — used by /referral/redemptions so a power-
    /// referrer with thousands of rows doesn't yank everything in one
    /// shot. Spring derives ORDER BY created_at DESC from the method name.
    Page<ReferralJpaEntity> findByReferrerUserIdOrderByCreatedAtDesc(
            String referrerUserId, Pageable pageable);

    long countByReferrerUserIdAndStatus(String referrerUserId, String status);

    long countByReferrerUserId(String referrerUserId);
}
