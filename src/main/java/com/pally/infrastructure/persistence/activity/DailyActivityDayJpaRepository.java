package com.pally.infrastructure.persistence.activity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface DailyActivityDayJpaRepository
        extends JpaRepository<DailyActivityDayJpaEntity, DailyActivityDayJpaEntity.PK> {

    /// Idempotent upsert — runs every time activity is credited; ON CONFLICT
    /// keeps the call cheap when the user is already counted for the day.
    @Modifying
    @Query(value = "INSERT INTO daily_activity_days(user_id, day) "
            + "VALUES (:userId, :day) ON CONFLICT DO NOTHING",
            nativeQuery = true)
    void recordDay(@Param("userId") String userId,
                   @Param("day") LocalDate day);

    @Query("SELECT d.day FROM DailyActivityDayJpaEntity d "
            + "WHERE d.userId = :userId AND d.day >= :since "
            + "ORDER BY d.day ASC")
    List<LocalDate> daysSince(@Param("userId") String userId,
                              @Param("since") LocalDate since);
}
