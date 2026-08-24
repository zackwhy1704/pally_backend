package com.pally.domain.account;

import java.time.Instant;

/**
 * A stored object whose delete FAILED during an account purge, kept so the file
 * stays discoverable instead of surviving with nothing pointing at it.
 *
 * <p>Deliberately carries NO user identifier. The storage key alone is enough to
 * finish the deletion, so keeping a user id on an erasure record would be
 * personal data retained without a purpose — which is the exposure this record
 * exists to remove, not one it should create. {@code avatarId} is retained only
 * because the avatar row is already deleted by the time this is written, so the
 * id carries no live identity and is useful when tracing an incident.
 *
 * @param attempts     retries so far; a high value is a terminally failing key
 *                     that should stay VISIBLE rather than be silently dropped
 * @param lastError    truncated failure message, for triage
 */
public record OrphanedStorageObject(
        String id,
        String storageKey,
        String avatarId,
        Instant failedAt,
        Instant lastAttemptAt,
        int attempts,
        String lastError
) {
    /** A freshly observed failure: zero attempts, never yet retried. */
    public static OrphanedStorageObject firstFailure(
            String id, String storageKey, String avatarId, Instant failedAt, String lastError) {
        return new OrphanedStorageObject(id, storageKey, avatarId, failedAt, null, 0, lastError);
    }
}
