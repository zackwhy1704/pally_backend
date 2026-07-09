package com.pally.shared.exception;

import java.time.Instant;

/**
 * ACCOUNT DELETION Phase 1: a DELETION_PENDING account tried to authenticate during the
 * grace window. Because the session_epoch bump is the wall, the sign-in path must NEVER
 * mint a normal session token for such an account — it returns this RESTORE SURFACE
 * instead, so login can't quietly become the hole in the wall.
 *
 * <p>Mapped to HTTP 403 with body
 * {@code {data:{code:"ACCOUNT_SCHEDULED_FOR_DELETION", graceEndsAt:...}, ...}} so the
 * client offers restore (POST /account/restore) rather than treating it as a bad password.
 */
public class AccountScheduledForDeletionException extends PallyException {

    public static final String CODE = "ACCOUNT_SCHEDULED_FOR_DELETION";

    private final Instant graceEndsAt;

    public AccountScheduledForDeletionException(Instant graceEndsAt) {
        super("This account is scheduled for deletion. Restore it to sign back in.", 403);
        this.graceEndsAt = graceEndsAt;
    }

    public Instant getGraceEndsAt() {
        return graceEndsAt;
    }
}
