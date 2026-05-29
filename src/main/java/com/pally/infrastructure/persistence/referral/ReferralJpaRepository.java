package com.pally.infrastructure.persistence.referral;

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

    long countByReferrerUserIdAndStatus(String referrerUserId, String status);

    long countByReferrerUserId(String referrerUserId);
}
