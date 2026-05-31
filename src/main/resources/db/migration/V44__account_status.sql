-- Account status for PDPA compliance.
-- ACTIVE  — 13+ self-consented, or under-13 parent-approved, or pre-existing users.
-- PENDING_CONSENT — under-13 waiting for parent email approval.
-- All existing users default to ACTIVE so they are unaffected.
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS account_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';

-- Backfill (should be a no-op since DEFAULT applies immediately).
UPDATE users SET account_status = 'ACTIVE' WHERE account_status IS NULL;

CREATE INDEX IF NOT EXISTS idx_users_account_status ON users(account_status);
