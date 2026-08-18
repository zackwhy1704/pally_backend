-- V129: syllabus_content_pack — pre-built, syllabus-tagged starter content a
-- teacher/student can SELECT instead of uploading their own material.
--
-- A pack is NOT a new content-storage concept: it is one hidden AvatarKind.SYLLABUS_PACK
-- avatar (Java-only enum value, `kind` column is a free VARCHAR(20), no DB change needed
-- for the enum itself) whose learning_modules/module_content_items are the EXISTING
-- generation/storage model. This table only maps (syllabus_code, topic_tag) -> that
-- avatar, plus the pack-level publish gate. Item-level servability stays on the
-- EXISTING module_content_items.status allow-list (LIVE/APPROVED) — this table never
-- duplicates that status.
--
-- Seeds one fixed platform-system user to own every syllabus-pack avatar (avatars.user_id
-- has no FK constraint, but every existing AvatarKind still populates it with a real,
-- traceable owner — this is that owner for content with no human owner).
INSERT INTO users (id, email, display_name, stars, xp, level, streak_days, created_at, setup_complete)
VALUES ('platform-syllabus-content-system', 'platform-syllabus-content@internal.apalchi.local',
        'Apalchi Content Library', 0, 0, 0, 0, now(), true)
ON CONFLICT (id) DO NOTHING;

CREATE TABLE syllabus_content_pack (
    id                   VARCHAR(36)  PRIMARY KEY,
    syllabus_code        VARCHAR(64)  NOT NULL,   -- e.g. 'SG-G3-COMPUTING-7155' — backend-only, never user-facing
    topic_tag            VARCHAR(100) NOT NULL,   -- e.g. 'Abstraction-and-Algorithms'
    avatar_id            VARCHAR(36)  NOT NULL REFERENCES avatars(id) ON DELETE CASCADE,
    pack_status          VARCHAR(16)  NOT NULL DEFAULT 'DRAFT', -- DRAFT | PUBLISHED | ARCHIVED
    source_license_note  VARCHAR(500),            -- which OER source(s) + license this pack was grounded on
    created_at           TIMESTAMPTZ  NOT NULL,
    UNIQUE (syllabus_code, topic_tag),
    UNIQUE (avatar_id)
);

CREATE INDEX idx_syllabus_content_pack_status ON syllabus_content_pack(pack_status);
