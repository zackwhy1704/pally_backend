-- V81: Multi-teacher staff membership.
-- org_staff rows link a teacher account to an org; ACTIVE|REMOVED status.
-- centre_invite_tokens gains a role column so owners can issue STAFF invites.

CREATE TABLE IF NOT EXISTS org_staff (
    id         VARCHAR(36)  PRIMARY KEY,
    org_id     VARCHAR(36)  NOT NULL,
    user_id    VARCHAR(36)  NOT NULL,
    role       VARCHAR(10)  NOT NULL DEFAULT 'STAFF',
    status     VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    removed_at TIMESTAMPTZ,
    UNIQUE (org_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_org_staff_user_id ON org_staff (user_id);
CREATE INDEX IF NOT EXISTS idx_org_staff_org_id  ON org_staff (org_id);

ALTER TABLE centre_invite_tokens
    ADD COLUMN IF NOT EXISTS role VARCHAR(10) NOT NULL DEFAULT 'OWNER';
