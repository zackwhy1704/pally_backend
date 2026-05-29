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

    @Query(value = """
            SELECT COUNT(*)
            FROM activity_log
            WHERE user_id = :userId
              AND created_at >= :since
            """, nativeQuery = true)
    Integer countSince(@Param("userId") String userId,
                       @Param("since") Instant since);

    @Query(value = """
            SELECT COALESCE(SUM(xp_earned), 0)
            FROM activity_log
            WHERE user_id = :userId
              AND created_at >= :since
            """, nativeQuery = true)
    Integer sumXpSince(@Param("userId") String userId,
                       @Param("since") Instant since);

    @Query(value = """
            SELECT COUNT(*)
            FROM activity_log
            WHERE user_id = :userId
              AND created_at >= :from
              AND created_at <  :to
            """, nativeQuery = true)
    Integer countBetween(@Param("userId") String userId,
                         @Param("from") Instant from,
                         @Param("to") Instant to);

    @Query(value = """
            SELECT COALESCE(SUM(xp_earned), 0)
            FROM activity_log
            WHERE user_id = :userId
              AND created_at >= :from
              AND created_at <  :to
            """, nativeQuery = true)
    Integer sumXpBetween(@Param("userId") String userId,
                         @Param("from") Instant from,
                         @Param("to") Instant to);

    @Query(value = """
            SELECT COUNT(*)
            FROM activity_log
            WHERE user_id = :userId
              AND activity_type = :type
              AND created_at >= :from
              AND created_at <  :to
            """, nativeQuery = true)
    Integer countByTypeBetween(@Param("userId") String userId,
                               @Param("type") String type,
                               @Param("from") Instant from,
                               @Param("to") Instant to);

    /// Count of activity rows for (user, type, avatar) in the half-open
    /// time range. Drives the per-avatar decay curve: 1st quiz of the day
    /// → 100% XP, 2nd → 50%, 3rd → 25%, 4th+ → 10%.
    @Query(value = """
            SELECT COUNT(*)
            FROM activity_log
            WHERE user_id = :userId
              AND activity_type = :type
              AND avatar_id = :avatarId
              AND created_at >= :from
              AND created_at <  :to
            """, nativeQuery = true)
    Integer countByTypeAndAvatarBetween(@Param("userId") String userId,
                                        @Param("type") String type,
                                        @Param("avatarId") String avatarId,
                                        @Param("from") Instant from,
                                        @Param("to") Instant to);

    /// True iff this user has done at least one activity of {@code type} on
    /// an avatar with {@code subject} between {@code from} and {@code to}.
    /// Drives the "first quiz of a new subject today" variety bonus.
    @Query(value = """
            SELECT COUNT(*)
            FROM activity_log al
            JOIN avatars a ON a.id = al.avatar_id
            WHERE al.user_id = :userId
              AND al.activity_type = :type
              AND a.subject = :subject
              AND al.created_at >= :from
              AND al.created_at <  :to
            """, nativeQuery = true)
    Integer countByTypeAndSubjectBetween(@Param("userId") String userId,
                                         @Param("type") String type,
                                         @Param("subject") String subject,
                                         @Param("from") Instant from,
                                         @Param("to") Instant to);
}
