-- B2B data layer (audit's M6). The Flutter app ships only the student
-- join flow; the centre admin dashboard is a separate web app on the
-- same Spring API.
--
-- organizations.owner_user_id is the gating principal — every /centre
-- endpoint must check the caller IS the owner of the org they're
-- querying (403 otherwise).

CREATE TABLE IF NOT EXISTS organizations (
    id              VARCHAR(36) PRIMARY KEY,
    name            VARCHAR(120) NOT NULL,
    type            VARCHAR(40)  NOT NULL DEFAULT 'TUITION_CENTRE',
    owner_user_id   VARCHAR(36)  NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    seat_limit      INT          NOT NULL DEFAULT 30,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_organizations_owner
    ON organizations(owner_user_id);

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS centre_id VARCHAR(36)
        REFERENCES organizations(id) ON DELETE SET NULL;
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS cohort_label VARCHAR(120);

CREATE INDEX IF NOT EXISTS idx_users_centre
    ON users(centre_id) WHERE centre_id IS NOT NULL;

-- Enrollment codes a centre issues so students can attach themselves to
-- a cohort. max_uses caps how many students can redeem one code; uses
-- counts redemptions. We're not building a full polymorphic codes table
-- yet — keeping CENTRE_ENROLL distinct from parent-link + referral
-- avoids cross-cutting validation that wouldn't share much logic.
CREATE TABLE IF NOT EXISTS centre_enroll_codes (
    code             VARCHAR(12) PRIMARY KEY,
    organization_id  VARCHAR(36) NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    cohort_label     VARCHAR(120) NOT NULL,
    max_uses         INT NOT NULL DEFAULT 30,
    uses             INT NOT NULL DEFAULT 0,
    expires_at       TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_centre_enroll_codes_org
    ON centre_enroll_codes(organization_id);
