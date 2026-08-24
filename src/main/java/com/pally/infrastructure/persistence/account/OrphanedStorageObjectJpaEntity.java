package com.pally.infrastructure.persistence.account;

import com.pally.domain.account.OrphanedStorageObject;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** JPA row for {@code orphaned_storage_object}. See V133 for why user_id is absent. */
@Entity
@Table(name = "orphaned_storage_object")
public class OrphanedStorageObjectJpaEntity {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "storage_key", nullable = false, columnDefinition = "TEXT")
    private String storageKey;

    @Column(name = "avatar_id", length = 36)
    private String avatarId;

    @Column(name = "failed_at", nullable = false)
    private Instant failedAt;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    protected OrphanedStorageObjectJpaEntity() {
    }

    public static OrphanedStorageObjectJpaEntity fromDomain(OrphanedStorageObject o) {
        OrphanedStorageObjectJpaEntity e = new OrphanedStorageObjectJpaEntity();
        e.id = o.id();
        e.storageKey = o.storageKey();
        e.avatarId = o.avatarId();
        e.failedAt = o.failedAt();
        e.lastAttemptAt = o.lastAttemptAt();
        e.attempts = o.attempts();
        e.lastError = o.lastError();
        return e;
    }

    public OrphanedStorageObject toDomain() {
        return new OrphanedStorageObject(
                id, storageKey, avatarId, failedAt, lastAttemptAt, attempts, lastError);
    }

    public String getStorageKey() {
        return storageKey;
    }
}
