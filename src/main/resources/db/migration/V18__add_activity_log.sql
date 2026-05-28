CREATE TABLE IF NOT EXISTS activity_log (
    id               VARCHAR(36) PRIMARY KEY,
    user_id          VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    activity_type    VARCHAR(20) NOT NULL,
    avatar_id        VARCHAR(36) REFERENCES avatars(id) ON DELETE SET NULL,
    duration_seconds INT NOT NULL DEFAULT 0,
    xp_earned        INT NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_activity_user_date ON activity_log(user_id, created_at);
