-- Part A1: group_type on study_groups, role on group_members, and a class_id
-- link so a centre class can own a CLASS-type study group whose membership
-- syncs from class enrolment.

ALTER TABLE study_groups ADD COLUMN IF NOT EXISTS group_type VARCHAR(10) NOT NULL DEFAULT 'PEER';
ALTER TABLE study_groups ADD COLUMN IF NOT EXISTS class_id VARCHAR(36);

-- group_members.role already exists from V26 (OWNER/MEMBER); this is idempotent
-- and the TEACHER value is added at the application layer.
ALTER TABLE group_members ADD COLUMN IF NOT EXISTS role VARCHAR(10) NOT NULL DEFAULT 'MEMBER';

-- A class owns at most one CLASS group. Partial unique index so PEER groups
-- (class_id NULL) are unaffected.
CREATE UNIQUE INDEX IF NOT EXISTS uq_study_groups_class_id
    ON study_groups (class_id) WHERE class_id IS NOT NULL;

-- System posts: plain-text announcements pushed into a (CLASS) group's feed by
-- the backend — e.g. "Answers for X are out", muddiest-point and challenge
-- notices. Distinct from group_shared_notes (which require a wiki page).
CREATE TABLE group_system_post (
    id          VARCHAR(36)  PRIMARY KEY,
    group_id    VARCHAR(36)  NOT NULL,
    kind        VARCHAR(30)  NOT NULL,
    body        TEXT         NOT NULL,
    ref_id      VARCHAR(36),
    created_at  TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_group_system_post_group ON group_system_post (group_id, created_at DESC);

