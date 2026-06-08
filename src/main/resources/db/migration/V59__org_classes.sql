-- V59: Classes as first-class units on the organizations model.
--
-- A class (subject×level) is the atom of the centre product: it owns a join code,
-- a shared corpus (wiki_pages.class_id), a branded Mochi (character + brand + accent
-- + cosmetic accessory slots), and its own analytics. A centre (organizations row)
-- has many classes. Students join the centre (users.centre_id via redeem-enroll-code),
-- then the admin assigns them into classes; each assignment provisions one branded
-- centre avatar pointing at the class corpus.
--
-- Builds on `organizations` (ADR 0001 — the canonical centre model). Does NOT
-- reintroduce the orphaned V56 centre_* schema dropped in V58.

-- ── The class ─────────────────────────────────────────────────────────────────
CREATE TABLE org_class (
    id               VARCHAR(36)  PRIMARY KEY,
    organization_id  VARCHAR(36)  NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name             VARCHAR(150) NOT NULL,
    subject          VARCHAR(80),
    level            VARCHAR(50),
    join_code        VARCHAR(12)  NOT NULL UNIQUE,
    -- The hidden avatar that holds this class's shared wiki corpus. Content
    -- uploaded "to the class" lands here; every student's centre avatar reads it.
    corpus_avatar_id VARCHAR(36),
    character_type   VARCHAR(30)  NOT NULL DEFAULT 'MOCHI',
    brand_name       VARCHAR(120),
    accent_color     VARCHAR(9),
    exam_date        DATE,
    -- Cosmetic accessory slots (Part 3 — inert until layered art exists).
    cosmetic_eyewear VARCHAR(40),
    cosmetic_clothes VARCHAR(40),
    cosmetic_shoes   VARCHAR(40),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_org_class_org ON org_class(organization_id);

-- ── Student membership of a class ─────────────────────────────────────────────
CREATE TABLE class_membership (
    id                 VARCHAR(36) PRIMARY KEY,
    class_id           VARCHAR(36) NOT NULL REFERENCES org_class(id) ON DELETE CASCADE,
    user_id            VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    student_avatar_id  VARCHAR(36),  -- the provisioned centre avatar (nullable until assigned)
    status             VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                         CHECK (status IN ('ACTIVE','REMOVED')),
    joined_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (class_id, user_id)
);
CREATE INDEX idx_class_membership_user  ON class_membership(user_id);
CREATE INDEX idx_class_membership_class ON class_membership(class_id);

-- ── Link avatars + wiki pages to a class ──────────────────────────────────────
-- A centre avatar belongs to a class; its closed-book chat reads the class corpus.
-- A centre avatar belongs to a class (for analytics grouping) and redirects its
-- closed-book retrieval to the class's corpus avatar.
ALTER TABLE avatars
    ADD COLUMN IF NOT EXISTS class_id            VARCHAR(36),
    ADD COLUMN IF NOT EXISTS corpus_avatar_id    VARCHAR(36),
    ADD COLUMN IF NOT EXISTS centre_brand_name   VARCHAR(120),
    ADD COLUMN IF NOT EXISTS centre_accent_color VARCHAR(9),
    ADD COLUMN IF NOT EXISTS cosmetic_eyewear    VARCHAR(40),
    ADD COLUMN IF NOT EXISTS cosmetic_clothes    VARCHAR(40),
    ADD COLUMN IF NOT EXISTS cosmetic_shoes      VARCHAR(40);
CREATE INDEX IF NOT EXISTS idx_avatar_class ON avatars(class_id) WHERE class_id IS NOT NULL;
