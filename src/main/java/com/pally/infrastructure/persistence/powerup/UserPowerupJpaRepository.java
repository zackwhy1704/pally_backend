package com.pally.infrastructure.persistence.powerup;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface UserPowerupJpaRepository
        extends JpaRepository<UserPowerupJpaEntity, UserPowerupJpaEntity.Id> {

    List<UserPowerupJpaEntity> findById_UserId(String userId);

    /// UPSERT — insert with count=:delta or bump an existing row by :delta.
    /// Atomic + idempotent enough that two concurrent grants don't lose
    /// one another.
    @Modifying
    @Query(value = """
            INSERT INTO user_powerups (user_id, type, count, updated_at)
            VALUES (:userId, :type, :delta, NOW())
            ON CONFLICT (user_id, type)
            DO UPDATE SET count = user_powerups.count + :delta,
                          updated_at = NOW()
            """, nativeQuery = true)
    int upsertCount(@Param("userId") String userId,
                    @Param("type") String type,
                    @Param("delta") int delta);

    /// Atomic consume — only decrements when count > 0. Returns 0 if the
    /// user had no tokens of that type. The shop service treats 0 as
    /// "out of tokens" → 400.
    @Modifying
    @Query(value = """
            UPDATE user_powerups
               SET count = count - 1,
                   updated_at = NOW()
             WHERE user_id = :userId
               AND type = :type
               AND count > 0
            """, nativeQuery = true)
    int consume(@Param("userId") String userId,
                @Param("type") String type);
}
