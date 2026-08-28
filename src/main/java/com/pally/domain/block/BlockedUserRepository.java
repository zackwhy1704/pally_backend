package com.pally.domain.block;

import java.util.List;
import java.util.Set;

/**
 * Domain port for user blocking (App Store Guideline 1.2).
 *
 * <p>A PORT rather than a direct JPA dependency so the domain does not import
 * {@code infrastructure.persistence} — the rule enforced by
 * {@code DomainLayeringGuardTest}, whose allow-list only ever shrinks.
 */
public interface BlockedUserRepository {

    /**
     * The set of users {@code blockerUserId} has blocked.
     *
     * <p>Returns a SET because the caller filters a list against it once per
     * request; a list would turn every group-detail load into an O(n*m) scan.
     */
    Set<String> blockedBy(String blockerUserId);

    /** Idempotent: re-blocking an already-blocked user is a no-op, not a duplicate. */
    void block(String blockerUserId, String blockedUserId);

    /** Idempotent: unblocking someone who was never blocked is a no-op. */
    void unblock(String blockerUserId, String blockedUserId);

    /** Blocked users with their display names, for the "who have I blocked?" list. */
    List<BlockedUserView> listBlocked(String blockerUserId);

    /** A blocked user as shown in the manage-blocks list. */
    record BlockedUserView(String userId, String displayName, java.time.Instant blockedAt) {}
}
