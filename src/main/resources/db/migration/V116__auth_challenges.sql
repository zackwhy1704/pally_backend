-- AUTH HARDENING A (Phase 3a challenges + forgot-password) — one single-use token store,
-- two consumers: LINK_SOCIAL (6-digit in-app code, 10-min TTL) and PASSWORD_RESET
-- (web-link token, ≤1h TTL). Same mechanics as consent_requests (V45): hashed secret,
-- status, expiry, attempt cap. The code/token is NEVER stored in plaintext.
CREATE TABLE auth_challenges (
    id            VARCHAR(36)  PRIMARY KEY,
    user_id       VARCHAR(36)  NOT NULL,
    purpose       VARCHAR(20)  NOT NULL,          -- LINK_SOCIAL | PASSWORD_RESET
    code_hash     VARCHAR(128) NOT NULL,          -- SHA-256 of the code/token
    provider      VARCHAR(20),                    -- LINK_SOCIAL: which provider to attach
    provider_sub  VARCHAR(255),                   -- LINK_SOCIAL: the sub to stamp
    status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING', -- PENDING | CONSUMED | EXPIRED
    attempts      INT          NOT NULL DEFAULT 0, -- wrong-code attempts (cap → invalidate)
    created_at    TIMESTAMPTZ  NOT NULL,
    expires_at    TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_auth_challenges_user_purpose ON auth_challenges (user_id, purpose, status);
