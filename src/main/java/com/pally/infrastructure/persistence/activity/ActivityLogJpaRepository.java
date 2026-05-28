package com.pally.infrastructure.persistence.activity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface ActivityLogJpaRepository extends JpaRepository<ActivityLogJpaEntity, String> {

    /// Total minutes (rounded down) of activity for [userId] in the
    /// half-open range [from, to). Returns 0 if no rows match.
    @Query(value = """
            SELECT COALESCE(SUM(duration_seconds) / 60, 0)
            FROM activity_log
            WHERE user_id = :userId
              AND created_at >= :from
              AND created_at <  :to
            """, nativeQuery = true)
    Integer sumMinutesBetween(@Param("userId") String userId,
                              @Param("from") Instant from,
                              @Param("to") Instant to);
}
