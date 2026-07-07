package com.pally.infrastructure.persistence.cost;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface AiUsageJpaRepository extends JpaRepository<AiUsageJpaEntity, String> {

    /** [userId, callType, sumCostMicros, callCount] per (user, callType) in range. */
    @Query("""
            SELECT u.userId, u.callType, COALESCE(SUM(u.estCostMicros), 0), COUNT(u)
            FROM AiUsageJpaEntity u
            WHERE u.createdAt >= :from AND u.createdAt < :to
            GROUP BY u.userId, u.callType
            """)
    List<Object[]> summarizeRaw(@Param("from") Instant from, @Param("to") Instant to);
}
