-- Add persistence columns to chat_messages
ALTER TABLE chat_messages
    ADD COLUMN IF NOT EXISTS feedback_type    VARCHAR(20),
    ADD COLUMN IF NOT EXISTS saved_to_brain   BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS is_photo_message BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS source_wiki_slug VARCHAR(200),
    ADD COLUMN IF NOT EXISTS message_type     VARCHAR(20) NOT NULL DEFAULT 'text';

-- Session states (replaces in-memory session tracking)
CREATE TABLE IF NOT EXISTS session_states (
    id              VARCHAR(36) PRIMARY KEY,
    avatar_id       VARCHAR(36) NOT NULL REFERENCES avatars(id) ON DELETE CASCADE,
    session_date    DATE        NOT NULL,
    topics_covered  TEXT        NOT NULL DEFAULT '[]',
    concepts_mastered TEXT      NOT NULL DEFAULT '[]',
    last_struggle   TEXT,
    questions_asked INT         NOT NULL DEFAULT 0,
    last_topic_slug VARCHAR(200),
    updated_at      TIMESTAMPTZ NOT NULL,
    UNIQUE (avatar_id, session_date)
);

CREATE INDEX IF NOT EXISTS idx_session_avatar ON session_states(avatar_id, session_date DESC);

-- Index to support "fetch last N messages by avatar" efficiently
CREATE INDEX IF NOT EXISTS idx_chat_avatar_created ON chat_messages(avatar_id, created_at DESC);
