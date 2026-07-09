package com.pally.infrastructure.persistence.cost;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface AiUsageJpaRepository extends JpaRepository<AiUsageJpaEntity, String> {

    /// ACCOUNT DELETION Phase 1 SURVIVOR (anonymize-in-place, keep rows): the cost
    /// ledger survives deletion but must not point at the erased identity. No FK, so
    /// these rows would otherwise retain the dead user_id forever (pre-existing gap).
    @Modifying
    @Query("UPDATE AiUsageJpaEntity a SET a.userId = null WHERE a.userId = :userId")
    int anonymizeByUserId(@Param("userId") String userId);

    @Modifying
    @Query("UPDATE AiUsageJpaEntity a SET a.avatarId = null WHERE a.avatarId = :avatarId")
    int anonymizeByAvatarId(@Param("avatarId") String avatarId);

    /** [userId, callType, sumCostMicros, callCount] per (user, callType) in range. */
    @Query("""
            SELECT u.userId, u.callType, COALESCE(SUM(u.estCostMicros), 0), COUNT(u)
            FROM AiUsageJpaEntity u
            WHERE u.createdAt >= :from AND u.createdAt < :to
            GROUP BY u.userId, u.callType
            """)
    List<Object[]> summarizeRaw(@Param("from") Instant from, @Param("to") Instant to);
}
