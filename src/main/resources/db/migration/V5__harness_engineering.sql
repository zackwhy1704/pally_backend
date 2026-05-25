-- V5: Harness engineering — progressive context assembly, session state, feedback

-- wiki_pages: harness tracking columns
ALTER TABLE wiki_pages
    ADD COLUMN IF NOT EXISTS last_retrieved_at  TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS quiz_use_count      INT                NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS certainty_score     DOUBLE PRECISION   NOT NULL DEFAULT 0.5,
    ADD COLUMN IF NOT EXISTS status              VARCHAR(20)        NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN IF NOT EXISTS review_required     BOOLEAN            NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS prerequisite_slugs  TEXT;

CREATE INDEX IF NOT EXISTS idx_wiki_status
    ON wiki_pages(avatar_id, status);

CREATE INDEX IF NOT EXISTS idx_wiki_retrieved
    ON wiki_pages(avatar_id, last_retrieved_at DESC NULLS LAST);

-- chat_messages: harness assembly trace (stored as text/JSONB)
ALTER TABLE chat_messages
    ADD COLUMN IF NOT EXISTS harness_trace  TEXT;

-- session_states: per-avatar per-day session tracking
CREATE TABLE IF NOT EXISTS session_states (
    id               VARCHAR(36)  PRIMARY KEY,
    avatar_id        VARCHAR(36)  NOT NULL REFERENCES avatars(id) ON DELETE CASCADE,
    user_id          VARCHAR(36)  NOT NULL,
    session_date     DATE         NOT NULL,
    topics_covered   TEXT,
    concepts_mastered TEXT,
    struggles        TEXT,
    message_count    INT          NOT NULL DEFAULT 0,
    started_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    UNIQUE (avatar_id, user_id, session_date)
);
CREATE INDEX IF NOT EXISTS idx_session_avatar
    ON session_states(avatar_id, session_date DESC);

-- chat_feedback: per-message engagement signals
CREATE TABLE IF NOT EXISTS chat_feedback (
    id             VARCHAR(36) PRIMARY KEY,
    message_id     VARCHAR(36) NOT NULL REFERENCES chat_messages(id) ON DELETE CASCADE,
    avatar_id      VARCHAR(36) NOT NULL,
    user_id        VARCHAR(36) NOT NULL,
    feedback_type  VARCHAR(30) NOT NULL,
    feedback_value VARCHAR(20),
    created_at     TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_feedback_message
    ON chat_feedback(message_id);
CREATE INDEX IF NOT EXISTS idx_feedback_avatar
    ON chat_feedback(avatar_id, created_at DESC);
