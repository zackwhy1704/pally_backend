-- Collaborative study groups. Pilot-gated client-side via the
-- groups_enabled feature flag (V24); the endpoints themselves are open to
-- any authenticated caller so we can iterate without admin gating logic.
CREATE TABLE IF NOT EXISTS study_groups (
    id           VARCHAR(36) PRIMARY KEY,
    name         VARCHAR(100) NOT NULL,
    subject      VARCHAR(30),
    invite_code  VARCHAR(12) UNIQUE NOT NULL,
    created_by   VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS group_members (
    group_id  VARCHAR(36) NOT NULL REFERENCES study_groups(id) ON DELETE CASCADE,
    user_id   VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role      VARCHAR(10) NOT NULL DEFAULT 'MEMBER', -- OWNER, MEMBER
    joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (group_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_group_members_user
    ON group_members(user_id);

CREATE TABLE IF NOT EXISTS group_shared_notes (
    id            VARCHAR(36) PRIMARY KEY,
    group_id      VARCHAR(36) NOT NULL REFERENCES study_groups(id) ON DELETE CASCADE,
    wiki_page_id  VARCHAR(36) NOT NULL,
    title         VARCHAR(255),
    shared_by     VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    shared_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_group_shared_notes_group
    ON group_shared_notes(group_id, shared_at DESC);
