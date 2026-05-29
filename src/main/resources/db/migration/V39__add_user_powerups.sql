-- Powerup inventory per user. Each row is a type the user has at least
-- one of; count goes up on shop purchase, down on consume. A row at
-- count=0 is preserved so the consume path doesn't need an "insert if
-- missing" branch — but the consume guard is `count > 0`, so spending
-- a token you don't have fails atomically.

CREATE TABLE IF NOT EXISTS user_powerups (
    user_id     VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type        VARCHAR(40) NOT NULL,   -- HINT_TOKEN | DOUBLE_XP | BONUS_QUIZ
    count       INT NOT NULL DEFAULT 0 CHECK (count >= 0),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, type)
);

CREATE INDEX IF NOT EXISTS idx_user_powerups_user ON user_powerups(user_id);
