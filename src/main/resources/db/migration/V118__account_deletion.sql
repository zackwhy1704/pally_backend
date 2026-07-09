-- ACCOUNT DELETION Phase 1 — grace-period lifecycle.
--
-- A user requests deletion; the account moves to account_status = 'DELETION_PENDING'
-- (already a legal value of the existing users.account_status VARCHAR(20) column —
-- there is NO CHECK constraint to widen, so no type change is needed) and
-- deletion_requested_at is stamped. A scheduled DeletionPurgeReaper permanently
-- purges the account account.deletion.grace-days (default 14) after this timestamp;
-- logging in during grace, or the emailed single-use restore link, clears it.
--
-- NOTE ON THE ACTUAL BLOCK: the thing that blocks every outstanding session the
-- instant deletion is requested is the users.session_epoch bump (see
-- JwtAuthenticationFilter, which rejects any token minted below the current epoch) —
-- NOT this column, and NOT ConsentGuard (whose requireActive is only called at
-- specific gated ingress, not on every request). This column only records WHEN the
-- grace clock started so the reaper knows when the window has elapsed.
ALTER TABLE users ADD COLUMN IF NOT EXISTS deletion_requested_at TIMESTAMPTZ;

-- Last time the purge reaper ATTEMPTED this account (set on abort/failure; the row is
-- gone on success). The reaper excludes rows attempted within a backoff window so that
-- permanently-stuck accounts (org acquired during grace, or a repeatedly-failing purge)
-- can never monopolize the ORDER BY deletion_requested_at ASC head of the queue and
-- starve healthy purges behind them. Nullable — never attempted yet.
ALTER TABLE users ADD COLUMN IF NOT EXISTS deletion_last_attempt_at TIMESTAMPTZ;

-- The reaper scans daily for DELETION_PENDING accounts whose grace has elapsed. A
-- partial index keeps that scan cheap no matter how large the users table grows.
CREATE INDEX IF NOT EXISTS idx_users_deletion_pending
    ON users (deletion_requested_at)
    WHERE account_status = 'DELETION_PENDING';
