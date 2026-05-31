-- Safety flags written by ModerationService when a child message or a
-- model reply is classified as high-risk. Parent can see these (PIN-gated).
CREATE TABLE IF NOT EXISTS chat_safety_flags (
    id            VARCHAR(36)  PRIMARY KEY,
    child_user_id VARCHAR(36)  NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    avatar_id     VARCHAR(36),
    message_id    VARCHAR(36),
    category      VARCHAR(40)  NOT NULL, -- SELF_HARM | VIOLENCE | SEXUAL | BULLYING | PERSONAL_DATA | OFF_TOPIC
    severity      VARCHAR(10)  NOT NULL, -- HIGH | MEDIUM | LOW
    snippet       TEXT,                  -- first 200 chars of the flagged text (for parent review)
    source        VARCHAR(10)  NOT NULL DEFAULT 'INPUT', -- INPUT | OUTPUT
    resolved      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_safety_flags_child
    ON chat_safety_flags(child_user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_safety_flags_unresolved
    ON chat_safety_flags(child_user_id, resolved)
    WHERE NOT resolved;
