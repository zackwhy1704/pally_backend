package com.pally.domain.account.port;

import com.pally.domain.account.OrphanedStorageObject;

import java.time.Instant;
import java.util.List;

/**
 * Domain port for the failed-storage-delete queue.
 *
 * <p>A PORT rather than a direct JPA dependency so this stays usable from
 * {@code domain} without importing {@code infrastructure.persistence} — the
 * layering rule enforced by {@code DomainLayeringGuardTest}, whose allow-list
 * only ever shrinks.
 */
public interface OrphanedStorageObjectRepository {

    /**
     * Records a failure, or leaves the existing row untouched if this key is
     * already queued.
     *
     * <p>Idempotent on {@code storageKey} by design: the sweeper retries the same
     * key repeatedly, and inserting per attempt would make the queue depth
     * measure retries instead of leaked objects.
     */
    void recordFailure(OrphanedStorageObject failure);

    /** Oldest-first batch for the sweeper. */
    List<OrphanedStorageObject> findOldest(int limit);

    /** Clears a key that has since been deleted successfully. */
    void deleteByStorageKey(String storageKey);

    /** Marks a retry that failed again: bumps attempts, stores the error. */
    void markRetryFailed(String storageKey, Instant attemptedAt, String error);

    /** Queue depth — a non-zero value is leaked objects awaiting deletion. */
    long count();
}
