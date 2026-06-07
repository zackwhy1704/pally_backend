-- V56: Centre Mochi multi-tenant B2B schema
-- Centres = tuition centres / schools; students join via join_code.
-- Centre Mochi avatars are injected into student home screens and are
-- hard-locked closed-book (only answer from the centre's wiki_chunk corpus).

CREATE TABLE IF NOT EXISTS centre (
    id            VARCHAR(36)  PRIMARY KEY,
    name          VARCHAR(200) NOT NULL,
    display_name  VARCHAR(200) NOT NULL,
    accent_color  VARCHAR(9),
    status        VARCHAR(20)  NOT NULL DEFAULT 'PILOT'
                    CHECK (status IN ('ACTIVE','SUSPENDED','PILOT')),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS centre_user (
    id             VARCHAR(36)  PRIMARY KEY,
    centre_id      VARCHAR(36)  NOT NULL REFERENCES centre(id) ON DELETE CASCADE,
    email          VARCHAR(255) NOT NULL,
    password_hash  VARCHAR(100) NOT NULL,
    role           VARCHAR(20)  NOT NULL DEFAULT 'TUTOR' CHECK (role IN ('OWNER','TUTOR')),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (centre_id, email)
);
CREATE INDEX IF NOT EXISTS idx_centre_user_centre ON centre_user(centre_id);

CREATE TABLE IF NOT EXISTS centre_class (
    id              VARCHAR(36)  PRIMARY KEY,
    centre_id       VARCHAR(36)  NOT NULL REFERENCES centre(id) ON DELETE CASCADE,
    name            VARCHAR(150) NOT NULL,
    level           VARCHAR(50),
    subject         VARCHAR(80),
    join_code       VARCHAR(12)  NOT NULL UNIQUE,
    seat_cap        INT          NOT NULL DEFAULT 50,
    character_type  VARCHAR(20)  NOT NULL DEFAULT 'MOCHI',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_class_centre ON centre_class(centre_id);

CREATE TABLE IF NOT EXISTS centre_enrolment (
    id          VARCHAR(36)  PRIMARY KEY,
    centre_id   VARCHAR(36)  NOT NULL REFERENCES centre(id) ON DELETE CASCADE,
    class_id    VARCHAR(36)  NOT NULL REFERENCES centre_class(id) ON DELETE CASCADE,
    user_id     VARCHAR(36)  NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    avatar_id   VARCHAR(36),
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','REMOVED','LAPSED')),
    joined_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (class_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_enrolment_centre  ON centre_enrolment(centre_id);
CREATE INDEX IF NOT EXISTS idx_enrolment_user    ON centre_enrolment(user_id);
CREATE INDEX IF NOT EXISTS idx_enrolment_class   ON centre_enrolment(class_id);

CREATE TABLE IF NOT EXISTS content_source (
    id            VARCHAR(36)  PRIMARY KEY,
    centre_id     VARCHAR(36)  NOT NULL REFERENCES centre(id) ON DELETE CASCADE,
    filename      VARCHAR(300) NOT NULL,
    mime          VARCHAR(120),
    storage_path  VARCHAR(600) NOT NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'UPLOADED'
                    CHECK (status IN ('UPLOADED','PARSED','COMPILED','FAILED')),
    uploaded_by   VARCHAR(36)  REFERENCES centre_user(id),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_source_centre ON content_source(centre_id);

CREATE TABLE IF NOT EXISTS wiki_topic (
    id          VARCHAR(36)  PRIMARY KEY,
    centre_id   VARCHAR(36)  NOT NULL REFERENCES centre(id) ON DELETE CASCADE,
    subject     VARCHAR(80),
    title       VARCHAR(250) NOT NULL,
    summary     TEXT,
    ordering    INT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_topic_centre ON wiki_topic(centre_id);

CREATE TABLE IF NOT EXISTS wiki_chunk (
    id                 VARCHAR(36) PRIMARY KEY,
    centre_id          VARCHAR(36) NOT NULL REFERENCES centre(id) ON DELETE CASCADE,
    topic_id           VARCHAR(36) NOT NULL REFERENCES wiki_topic(id) ON DELETE CASCADE,
    text               TEXT        NOT NULL,
    source_content_id  VARCHAR(36) REFERENCES content_source(id),
    ordering           INT         NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_chunk_centre ON wiki_chunk(centre_id);
CREATE INDEX IF NOT EXISTS idx_chunk_topic  ON wiki_chunk(topic_id);

CREATE TABLE IF NOT EXISTS centre_question (
    id               VARCHAR(36)  PRIMARY KEY,
    centre_id        VARCHAR(36)  NOT NULL REFERENCES centre(id) ON DELETE CASCADE,
    topic_id         VARCHAR(36)  NOT NULL REFERENCES wiki_topic(id) ON DELETE CASCADE,
    stem             TEXT         NOT NULL,
    options_json     JSONB        NOT NULL,
    answer_key       VARCHAR(8)   NOT NULL,
    explanation      TEXT,
    source_chunk_id  VARCHAR(36)  REFERENCES wiki_chunk(id),
    difficulty       VARCHAR(10)  CHECK (difficulty IN ('EASY','MEDIUM','HARD')),
    review_status    VARCHAR(10)  NOT NULL DEFAULT 'DRAFT' CHECK (review_status IN ('DRAFT','APPROVED')),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_cquestion_centre ON centre_question(centre_id);
CREATE INDEX IF NOT EXISTS idx_cquestion_topic  ON centre_question(topic_id);

CREATE TABLE IF NOT EXISTS centre_flashcard (
    id               VARCHAR(36) PRIMARY KEY,
    centre_id        VARCHAR(36) NOT NULL REFERENCES centre(id) ON DELETE CASCADE,
    topic_id         VARCHAR(36) NOT NULL REFERENCES wiki_topic(id) ON DELETE CASCADE,
    front            TEXT        NOT NULL,
    back             TEXT        NOT NULL,
    source_chunk_id  VARCHAR(36) REFERENCES wiki_chunk(id),
    review_status    VARCHAR(10) NOT NULL DEFAULT 'DRAFT' CHECK (review_status IN ('DRAFT','APPROVED')),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_cflashcard_centre ON centre_flashcard(centre_id);
CREATE INDEX IF NOT EXISTS idx_cflashcard_topic  ON centre_flashcard(topic_id);

CREATE TABLE IF NOT EXISTS assignment (
    id           VARCHAR(36)  PRIMARY KEY,
    centre_id    VARCHAR(36)  NOT NULL REFERENCES centre(id) ON DELETE CASCADE,
    class_id     VARCHAR(36)  NOT NULL REFERENCES centre_class(id) ON DELETE CASCADE,
    title        VARCHAR(200) NOT NULL,
    description  TEXT,
    type         VARCHAR(20)  NOT NULL CHECK (type IN ('QUIZ','FLASHCARD_DECK','READING','PROMPT')),
    payload_json JSONB        NOT NULL,
    due_at       TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_assignment_centre ON assignment(centre_id);
CREATE INDEX IF NOT EXISTS idx_assignment_class  ON assignment(class_id);

CREATE TABLE IF NOT EXISTS student_assignment (
    id             VARCHAR(36)  PRIMARY KEY,
    assignment_id  VARCHAR(36)  NOT NULL REFERENCES assignment(id) ON DELETE CASCADE,
    user_id        VARCHAR(36)  NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    centre_id      VARCHAR(36)  NOT NULL REFERENCES centre(id) ON DELETE CASCADE,
    status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','IN_PROGRESS','DONE')),
    started_at     TIMESTAMPTZ,
    completed_at   TIMESTAMPTZ,
    UNIQUE (assignment_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_sassignment_user   ON student_assignment(user_id, centre_id);
CREATE INDEX IF NOT EXISTS idx_sassignment_assign ON student_assignment(assignment_id);

CREATE TABLE IF NOT EXISTS centre_chat_message (
    id                  VARCHAR(36) PRIMARY KEY,
    centre_id           VARCHAR(36) NOT NULL REFERENCES centre(id) ON DELETE CASCADE,
    class_id            VARCHAR(36) NOT NULL REFERENCES centre_class(id) ON DELETE CASCADE,
    user_id             VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role                VARCHAR(10) NOT NULL CHECK (role IN ('USER','MOCHI')),
    content             TEXT        NOT NULL,
    grounded_chunk_ids  JSONB,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_cchat_centre_user ON centre_chat_message(centre_id, user_id);
CREATE INDEX IF NOT EXISTS idx_cchat_class       ON centre_chat_message(class_id, created_at DESC);

-- Add centre linkage columns to existing avatars table
ALTER TABLE avatars
    ADD COLUMN IF NOT EXISTS is_centre_avatar      BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS centre_enrolment_id   VARCHAR(36) REFERENCES centre_enrolment(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS avatar_locked         BOOLEAN     NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_avatar_centre_enrolment
    ON avatars(centre_enrolment_id)
    WHERE is_centre_avatar = TRUE;
