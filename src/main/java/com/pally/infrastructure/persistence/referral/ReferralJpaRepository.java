package com.pally.infrastructure.persistence.referral;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
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

    /// Atomic once-only activation gate: flips pending→activated and returns the
    /// affected row count. Only the winner of a concurrent race gets 1 (and pays
    /// the reward); a second caller gets 0. Closes the check-then-act double-payout.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE ReferralJpaEntity r SET r.status = :activated, r.activatedAt = :now "
            + "WHERE r.id = :id AND r.status = :pending")
    int activateIfPending(@Param("id") String id,
                          @Param("pending") String pending,
                          @Param("activated") String activated,
                          @Param("now") Instant now);
}
