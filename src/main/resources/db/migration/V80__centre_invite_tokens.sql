-- V80: Invite-based centre provisioning.
-- Platform admin creates a token; prospective centre owner accepts it to
-- register their org without needing a public self-serve signup endpoint.
CREATE TABLE IF NOT EXISTS centre_invite_tokens (
    token          VARCHAR(64)  PRIMARY KEY,
    centre_name    VARCHAR(255) NOT NULL,
    contact_email  VARCHAR(255) NOT NULL,
    created_by     VARCHAR(36)  NOT NULL,   -- admin user id
    accepted_by    VARCHAR(36),             -- centre owner user id, null until accepted
    org_id         VARCHAR(36),             -- created org id, null until accepted
    expires_at     TIMESTAMPTZ  NOT NULL,
    accepted_at    TIMESTAMPTZ,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_centre_invite_tokens_contact_email
    ON centre_invite_tokens (contact_email);
