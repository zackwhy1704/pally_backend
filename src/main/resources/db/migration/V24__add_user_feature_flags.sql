-- Per-user feature flags for piloting opt-in modules (e.g. study groups).
--
-- Server-controlled: the absence of a row means "off". An admin endpoint
-- (POST/DELETE /api/v1/admin/users/{userId}/flags/{flagName}) toggles flags
-- for individual users without a redeploy. Flutter caches the resolved map
-- on app start and re-fetches on next launch.
CREATE TABLE IF NOT EXISTS user_feature_flags (
    user_id    VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    flag_name  VARCHAR(50) NOT NULL,
    enabled    BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, flag_name)
);

CREATE INDEX IF NOT EXISTS idx_uff_flag_name
    ON user_feature_flags(flag_name) WHERE enabled = TRUE;
