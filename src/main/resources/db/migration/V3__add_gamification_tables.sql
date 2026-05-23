-- V3: Users table, flashcards, quiz sessions, unlocked characters

CREATE TABLE IF NOT EXISTS users (
    id              VARCHAR(36)   PRIMARY KEY,
    email           VARCHAR(255)  UNIQUE,
    display_name    VARCHAR(100),
    parent_pin_hash VARCHAR(100),
    stars           INT           NOT NULL DEFAULT 0,
    xp              INT           NOT NULL DEFAULT 0,
    level           INT           NOT NULL DEFAULT 1,
    streak_days     INT           NOT NULL DEFAULT 0,
    last_active_date DATE,
    created_at      TIMESTAMPTZ   NOT NULL
);

-- Ensure dev-user row exists for development
INSERT INTO users (id, display_name, stars, xp, level, streak_days, created_at)
VALUES ('dev-user', 'Dev User', 120, 250, 2, 3, NOW())
ON CONFLICT (id) DO NOTHING;

CREATE TABLE IF NOT EXISTS flashcards (
    id              VARCHAR(36)   PRIMARY KEY,
    avatar_id       VARCHAR(36)   NOT NULL REFERENCES avatars(id) ON DELETE CASCADE,
    front           TEXT          NOT NULL,
    back            TEXT          NOT NULL,
    source_slug     VARCHAR(200),
    last_rating     VARCHAR(10),
    next_review_at  TIMESTAMPTZ,
    repetitions     INT           NOT NULL DEFAULT 0,
    ease_factor     REAL          NOT NULL DEFAULT 2.5,
    interval_days   INT           NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ   NOT NULL
);

CREATE TABLE IF NOT EXISTS quiz_sessions (
    id            VARCHAR(36)   PRIMARY KEY,
    avatar_id     VARCHAR(36)   NOT NULL REFERENCES avatars(id) ON DELETE CASCADE,
    user_id       VARCHAR(36)   NOT NULL,
    score         INT,
    total         INT,
    xp_earned     INT           NOT NULL DEFAULT 0,
    completed_at  TIMESTAMPTZ,
    created_at    TIMESTAMPTZ   NOT NULL
);

CREATE TABLE IF NOT EXISTS unlocked_characters (
    user_id        VARCHAR(36)   NOT NULL,
    character_type VARCHAR(20)   NOT NULL,
    unlocked_at    TIMESTAMPTZ   NOT NULL,
    PRIMARY KEY (user_id, character_type)
);

CREATE INDEX IF NOT EXISTS idx_flashcards_avatar ON flashcards(avatar_id, next_review_at);
CREATE INDEX IF NOT EXISTS idx_quiz_sessions_avatar ON quiz_sessions(avatar_id);
