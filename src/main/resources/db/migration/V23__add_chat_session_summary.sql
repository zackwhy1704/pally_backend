-- Rolling per-avatar session-state summary.
--
-- After each chat exchange, the summariser collapses the conversation into a
-- forward-looking note (~150 words). On the next turn, that note is prepended
-- to the system prompt as "What you remember about this student", giving the
-- tutor compounding context across sessions without bloating chat history.
--
-- One row per avatar — the summary is rolling (each update merges the prior
-- summary with the latest exchange).
CREATE TABLE IF NOT EXISTS chat_session_summary (
    avatar_id   VARCHAR(36) PRIMARY KEY REFERENCES avatars(id) ON DELETE CASCADE,
    summary     TEXT        NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_chat_session_summary_updated
    ON chat_session_summary(updated_at);
