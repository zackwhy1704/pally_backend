package com.pally.domain.user;

import com.pally.domain.account.AccountType;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UserRepository {

    Optional<User> findById(String userId);
    User save(User user);
    void deleteById(String userId);
    int countByParentId(String parentId);
    List<User> findByParentId(String parentId);
    List<User> findByAccountType(AccountType accountType);
    Optional<User> findByReferralCode(String referralCode);
    void ensureUserExists(String userId);
    int spendStars(String userId, int cost);
    int buyStreakFreeze(String userId, int cost, int cap);
    int earnStreakFreeze(String userId, int cap);
    int consumeStreakFreeze(String userId);
    XpResult addXpAndStars(String userId, int xp, int stars);

    /** Count students enrolled in a centre. */
    long countByCentreId(String centreId);

    /**
     * ACCOUNT DELETION Phase 1: move the account into the deletion grace window in a
     * single unit of work — set account_status = DELETION_PENDING, stamp
     * deletion_requested_at, and BUMP session_epoch. The epoch bump is the PRIMARY
     * block: it invalidates every outstanding token (JwtAuthenticationFilter rejects
     * tokens minted below the current epoch), so the account is logged out everywhere
     * the instant deletion is requested. Idempotent to re-apply.
     */
    void markDeletionPending(String userId, Instant requestedAt);

    /**
     * ACCOUNT DELETION Phase 1: up to {@code limit} accounts in DELETION_PENDING whose
     * grace has elapsed (deletion_requested_at &lt; cutoff), oldest first. Batch-limited
     * so the daily purge stays bounded; since purged rows are deleted, calling this
     * again returns the next batch (a resumable cursor). Returns domain Users so the
     * reaper stays free of any infrastructure.persistence import.
     */
    List<User> findDeletionPendingBefore(Instant cutoff, int limit);

    /**
     * ACCOUNT DELETION Phase 1 restore: cancel a pending deletion — set account_status =
     * ACTIVE, clear deletion_requested_at, and BUMP session_epoch AGAIN so any token
     * minted between the request and the restore also dies. Idempotent-safe.
     */
    void clearDeletionPending(String userId);

    /** Look up a user by (canonicalized) email — used by the password restore path. */
    Optional<User> findByEmail(String email);

    /** Batch-fetch users by their IDs (for heatmap display-name lookup). */
    List<User> findAllByIds(Collection<String> ids);

    // ── PIN management ────────────────────────────────────────────────────

    /** Returns the BCrypt hash of the parent PIN, or empty if none is set. */
    Optional<String> getParentPinHash(String userId);

    /** Overwrites the parent PIN hash for the given user. */
    void setParentPinHash(String userId, String bcryptHash);

    /** Returns the BCrypt hash of the user's login password, or empty if not set. */
    Optional<String> getPasswordHash(String userId);

    // ── Screen-time ───────────────────────────────────────────────────────

    /** Updates screen-time enforcement settings. */
    void setScreenTime(String userId, boolean enabled, int minutes);

    // ── FCM token ─────────────────────────────────────────────────────────

    void setFcmToken(String userId, String token);

    // ── Link-code (family pairing) ────────────────────────────────────────

    Optional<User> findByLinkCode(String code);

    /** Sets the link code and its expiry. */
    void setLinkCode(String userId, String code, Instant expiresAt);

    /** Clears the link code fields (used on code expiry during claim). */
    void clearLinkCode(String userId);

    record XpResult(int newXp, int oldLevel, int newLevel,
                    boolean levelledUp, String unlockedRewardLabel) {
        public XpResult(int newXp, int oldLevel, int newLevel, boolean levelledUp) {
            this(newXp, oldLevel, newLevel, levelledUp, null);
        }
        public static XpResult unchanged(int xp, int level) {
            return new XpResult(xp, level, level, false, null);
        }
    }
}
