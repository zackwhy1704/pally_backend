-- Item 1 / OWASP A01 — admin role on users so /api/v1/admin/** can be
-- locked to ADMIN-only. Values: USER (default) | ADMIN | CENTRE_ADMIN
-- (the prior account_type stays separate — that's the family-account
-- model; role is the authorization principal).

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS role VARCHAR(20) NOT NULL DEFAULT 'USER';

CREATE INDEX IF NOT EXISTS idx_users_role ON users(role) WHERE role <> 'USER';
