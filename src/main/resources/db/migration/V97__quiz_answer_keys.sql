-- Grade integrity: server-held answer key for generated quiz questions.
-- Persisted (not in-memory) so grading survives restart / a second instance.
-- The submit path grades against this key instead of the client-supplied
-- correctMap, so a tampered client cannot inflate teacher-visible "grasp".
CREATE TABLE quiz_answer_keys (
    question_id   VARCHAR(100) PRIMARY KEY,
    avatar_id     VARCHAR(36)  NOT NULL,
    correct_index INT          NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Cleanup of stale keys (a daily reaper deletes old rows) scans by age.
CREATE INDEX idx_quiz_answer_keys_created_at ON quiz_answer_keys (created_at);
