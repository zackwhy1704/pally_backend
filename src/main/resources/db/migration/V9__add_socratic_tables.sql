-- Teaching mode toggle on avatars (TEACHING = Socratic guidance, DIRECT = just answer)
ALTER TABLE avatars ADD COLUMN IF NOT EXISTS teaching_mode VARCHAR(20) NOT NULL DEFAULT 'TEACHING';

-- Pre-compiled hint trees per wiki page
CREATE TABLE IF NOT EXISTS hint_trees (
    id            VARCHAR(36) PRIMARY KEY,
    avatar_id     VARCHAR(36) NOT NULL REFERENCES avatars(id) ON DELETE CASCADE,
    wiki_slug     VARCHAR(200) NOT NULL,
    topic_keywords TEXT NOT NULL,
    hints_json    TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL,
    UNIQUE (avatar_id, wiki_slug)
);

-- Per-avatar chat sessions to track Socratic attempt counts
CREATE TABLE IF NOT EXISTS chat_sessions (
    id             VARCHAR(36) PRIMARY KEY,
    avatar_id      VARCHAR(36) NOT NULL REFERENCES avatars(id) ON DELETE CASCADE,
    current_topic  VARCHAR(200),
    attempt_count  INT NOT NULL DEFAULT 0,
    escape_fired   BOOLEAN NOT NULL DEFAULT FALSE,
    session_date   DATE NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL,
    UNIQUE (avatar_id, session_date)
);

CREATE INDEX IF NOT EXISTS idx_hint_trees_avatar ON hint_trees(avatar_id);
CREATE INDEX IF NOT EXISTS idx_chat_sessions_avatar ON chat_sessions(avatar_id, session_date DESC);
