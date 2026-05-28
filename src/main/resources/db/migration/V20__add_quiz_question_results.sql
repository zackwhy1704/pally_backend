CREATE TABLE IF NOT EXISTS quiz_question_results (
    id          VARCHAR(36) PRIMARY KEY,
    user_id     VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    avatar_id   VARCHAR(36) NOT NULL REFERENCES avatars(id) ON DELETE CASCADE,
    question_id VARCHAR(100) NOT NULL,
    topic_slug  VARCHAR(200),
    was_correct BOOLEAN NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_qqr_user_topic ON quiz_question_results(user_id, topic_slug);
CREATE INDEX IF NOT EXISTS idx_qqr_avatar ON quiz_question_results(avatar_id, created_at);
