package com.pally.infrastructure.persistence.account;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface OrphanedStorageObjectJpaRepository
        extends JpaRepository<OrphanedStorageObjectJpaEntity, String> {

    boolean existsByStorageKey(String storageKey);

    @Query("SELECT o FROM OrphanedStorageObjectJpaEntity o ORDER BY o.failedAt ASC")
    List<OrphanedStorageObjectJpaEntity> findOldest(Pageable pageable);

    @Modifying
    @Query("DELETE FROM OrphanedStorageObjectJpaEntity o WHERE o.storageKey = :key")
    int deleteByStorageKey(@Param("key") String key);

    /// Bumps the retry counter in place. A terminally failing key therefore keeps
    /// accumulating attempts and stays VISIBLE in the queue — it is never dropped.
    @Modifying
    @Query("""
            UPDATE OrphanedStorageObjectJpaEntity o
               SET o.attempts = o.attempts + 1,
                   o.lastAttemptAt = :at,
                   o.lastError = :error
             WHERE o.storageKey = :key
            """)
    int markRetryFailed(@Param("key") String key,
                        @Param("at") Instant at,
                        @Param("error") String error);
}
