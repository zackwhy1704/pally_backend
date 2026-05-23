-- V4: Feature improvements (P1-P10 sprint)

-- P4: Grade level + curriculum on avatar
ALTER TABLE avatars
    ADD COLUMN IF NOT EXISTS grade_level     VARCHAR(10),
    ADD COLUMN IF NOT EXISTS curriculum_type VARCHAR(30),
    ADD COLUMN IF NOT EXISTS pedagogy_mode   VARCHAR(20) NOT NULL DEFAULT 'SOCRATIC',
    ADD COLUMN IF NOT EXISTS test_date       DATE;

-- P7: Source type + topic tag on uploaded files
ALTER TABLE knowledge_files
    ADD COLUMN IF NOT EXISTS source_type VARCHAR(20),
    ADD COLUMN IF NOT EXISTS topic_tag   VARCHAR(100);

-- P8: Human correction on wiki pages
ALTER TABLE wiki_pages
    ADD COLUMN IF NOT EXISTS quality_score      INT            NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS human_correction   TEXT,
    ADD COLUMN IF NOT EXISTS correction_at      TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS is_human_verified  BOOLEAN        NOT NULL DEFAULT FALSE;

-- P6: Quiz answer records for error pattern tracking
CREATE TABLE IF NOT EXISTS quiz_answer_records (
    id             VARCHAR(36) PRIMARY KEY,
    avatar_id      VARCHAR(36) NOT NULL REFERENCES avatars(id) ON DELETE CASCADE,
    user_id        VARCHAR(36) NOT NULL,
    question_text  TEXT        NOT NULL,
    user_answer    TEXT        NOT NULL,
    correct_answer TEXT        NOT NULL,
    is_correct     BOOLEAN     NOT NULL,
    error_type     VARCHAR(20),
    topic_slug     VARCHAR(200),
    created_at     TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_quiz_answers_avatar
    ON quiz_answer_records(avatar_id, topic_slug, created_at DESC);

-- P9: Nudge events
CREATE TABLE IF NOT EXISTS nudge_events (
    id           VARCHAR(36) PRIMARY KEY,
    user_id      VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    nudge_type   VARCHAR(30) NOT NULL,
    avatar_id    VARCHAR(36),
    dismissed_at TIMESTAMPTZ,
    acted_at     TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_nudge_events_user
    ON nudge_events(user_id, created_at DESC);
