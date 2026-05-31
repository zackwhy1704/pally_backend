-- Parental consent tracking for PDPA compliance.

-- One row per consent request sent to a parent. A child account is
-- PENDING_CONSENT until this row reaches status='APPROVED'.
CREATE TABLE IF NOT EXISTS consent_requests (
    id             VARCHAR(36)  PRIMARY KEY,
    child_user_id  VARCHAR(36)  NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    parent_email   VARCHAR(255) NOT NULL,
    token          VARCHAR(128) UNIQUE NOT NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING', -- PENDING | APPROVED | DECLINED | EXPIRED
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at     TIMESTAMPTZ  NOT NULL,
    approved_at    TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_consent_req_child
    ON consent_requests(child_user_id);
CREATE INDEX IF NOT EXISTS idx_consent_req_token
    ON consent_requests(token);

-- Immutable audit log of consent given/withdrawn.
-- policy_version lets us re-consent if we change purposes.
CREATE TABLE IF NOT EXISTS consent_records (
    id             VARCHAR(36)  PRIMARY KEY,
    user_id        VARCHAR(36)  NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    consenter      VARCHAR(20)  NOT NULL,  -- 'SELF' | 'PARENT'
    method         VARCHAR(20)  NOT NULL,  -- 'EMAIL_LINK' | 'CHECKBOX'
    purposes       TEXT         NOT NULL,  -- JSON list of purposes consented to
    policy_version VARCHAR(20)  NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    withdrawn_at   TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_consent_rec_user
    ON consent_records(user_id);
