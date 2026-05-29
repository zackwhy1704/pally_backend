-- Curriculum layer: structured topic spine each avatar's wiki/quiz journey
-- can be mapped onto. Built-in curricula have owner_user_id NULL; uploaded
-- syllabi (parents/teachers) carry the uploader so they only see their own.
CREATE TABLE IF NOT EXISTS curricula (
    id              VARCHAR(36) PRIMARY KEY,
    name            VARCHAR(150) NOT NULL,
    subject         VARCHAR(30)  NOT NULL,
    grade           VARCHAR(20),
    owner_user_id   VARCHAR(36) REFERENCES users(id) ON DELETE CASCADE,
    is_default      BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_curricula_subject_grade
    ON curricula(subject, grade);

CREATE TABLE IF NOT EXISTS curriculum_topics (
    id              VARCHAR(36) PRIMARY KEY,
    curriculum_id   VARCHAR(36) NOT NULL REFERENCES curricula(id) ON DELETE CASCADE,
    name            VARCHAR(255) NOT NULL,
    slug            VARCHAR(200) NOT NULL,
    sequence        INT NOT NULL DEFAULT 0,
    parent_topic_id VARCHAR(36) REFERENCES curriculum_topics(id) ON DELETE CASCADE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_curriculum_topics_curriculum
    ON curriculum_topics(curriculum_id, sequence);

-- Each avatar can be attached to one curriculum (the "spine" the journey
-- maps onto). Null = no curriculum — legacy behaviour.
ALTER TABLE avatars
    ADD COLUMN IF NOT EXISTS curriculum_id VARCHAR(36)
    REFERENCES curricula(id) ON DELETE SET NULL;
