CREATE TABLE character_unlocks (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    character VARCHAR(30) NOT NULL,
    unlocked_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(user_id, character)
);
CREATE INDEX idx_character_unlocks_user ON character_unlocks(user_id);
