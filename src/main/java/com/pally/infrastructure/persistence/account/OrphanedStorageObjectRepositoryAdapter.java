package com.pally.infrastructure.persistence.account;

import com.pally.domain.account.OrphanedStorageObject;
import com.pally.domain.account.port.OrphanedStorageObjectRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** JPA adapter for {@link OrphanedStorageObjectRepository}. */
@Component
public class OrphanedStorageObjectRepositoryAdapter implements OrphanedStorageObjectRepository {

    private final OrphanedStorageObjectJpaRepository jpa;

    public OrphanedStorageObjectRepositoryAdapter(OrphanedStorageObjectJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    @Transactional
    public void recordFailure(OrphanedStorageObject failure) {
        // Idempotent on storage_key: the sweeper retries the same key, and a row
        // per attempt would make queue depth measure retries, not leaked objects.
        // The unique index in V133 is the real guarantee; this check just avoids
        // a pointless constraint violation on the common path.
        if (jpa.existsByStorageKey(failure.storageKey())) return;
        jpa.save(OrphanedStorageObjectJpaEntity.fromDomain(failure));
    }

    @Override
    public List<OrphanedStorageObject> findOldest(int limit) {
        return jpa.findOldest(PageRequest.of(0, limit)).stream()
                .map(OrphanedStorageObjectJpaEntity::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public void deleteByStorageKey(String storageKey) {
        jpa.deleteByStorageKey(storageKey);
    }

    @Override
    @Transactional
    public void markRetryFailed(String storageKey, Instant attemptedAt, String error) {
        jpa.markRetryFailed(storageKey, attemptedAt, error);
    }

    @Override
    public long count() {
        return jpa.count();
    }
}
