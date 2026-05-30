-- Tracks which study-plan tasks a user has completed today so the plan
-- generator can exclude them from subsequent fetches within the same day.
-- task_key is a deterministic string: "{avatarId}:{type}:{sgtDate}" for
-- quiz/flashcard tasks, "info:{sgtDate}" for the fallback task.
CREATE TABLE IF NOT EXISTS study_plan_completions (
    user_id   VARCHAR(36)  NOT NULL,
    task_key  VARCHAR(200) NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, task_key)
);

CREATE INDEX IF NOT EXISTS idx_spc_user_date
    ON study_plan_completions(user_id, completed_at);
