-- Data-driven Mochi catalog (Stage 5 of the reward system spec).
--
-- Replaces the hardcoded ALL_CHARACTERS list in CharacterShopService.
-- A new 8-Mochi theme now ships as catalog rows, not a deploy.
--
-- We KEEP character_unlocks untouched for backward compat — ownership
-- is also mirrored into user_mochi via a one-off backfill so future
-- code (collection screen, set-completion rewards) can read a single
-- source of truth. The catalog table is the source of truth for the
-- character LIST + rarity + cost + active windows.

CREATE TABLE IF NOT EXISTS mochi_themes (
    id              VARCHAR(36) PRIMARY KEY,
    name            VARCHAR(80) NOT NULL,
    season_label    VARCHAR(80),
    active_from     TIMESTAMPTZ,
    active_until    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS mochi_characters (
    id              VARCHAR(40) PRIMARY KEY,
    name            VARCHAR(80) NOT NULL,
    theme_id        VARCHAR(36) REFERENCES mochi_themes(id),
    rarity          VARCHAR(10) NOT NULL,           -- COMMON | RARE | SECRET
    acquisition     VARCHAR(12) NOT NULL,           -- DEFAULT|STAR_SHOP|MYSTERY_BOX|LEVEL|SEASONAL
    star_cost       INT,
    active_from     TIMESTAMPTZ,
    active_until    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_mochi_characters_theme
    ON mochi_characters(theme_id);
CREATE INDEX IF NOT EXISTS idx_mochi_characters_acquisition
    ON mochi_characters(acquisition);

CREATE TABLE IF NOT EXISTS user_mochi (
    user_id         VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    mochi_id        VARCHAR(40) NOT NULL REFERENCES mochi_characters(id),
    acquired_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    acquired_via    VARCHAR(12),
    PRIMARY KEY (user_id, mochi_id)
);

CREATE INDEX IF NOT EXISTS idx_user_mochi_user ON user_mochi(user_id);

-- Seed the "Core" theme. Always active (active_from/until = NULL means
-- no window restriction). Preserves the existing 8 ids so the shop
-- behaviour is byte-identical for current users.
INSERT INTO mochi_themes (id, name, season_label)
VALUES ('THEME_CORE', 'Core Mochis', 'Always available')
ON CONFLICT (id) DO NOTHING;

-- 6 default unlocks. acquisition=DEFAULT means seedDefaultUnlocks() grants
-- them on first login (preserves existing behaviour for new users).
INSERT INTO mochi_characters (id, name, theme_id, rarity, acquisition, star_cost) VALUES
    ('PENCIL',     'Pencil',     'THEME_CORE', 'COMMON', 'DEFAULT', NULL),
    ('SCIENCE',    'Science',    'THEME_CORE', 'COMMON', 'DEFAULT', NULL),
    ('PE',         'PE',         'THEME_CORE', 'COMMON', 'DEFAULT', NULL),
    ('ART',        'Art',        'THEME_CORE', 'COMMON', 'DEFAULT', NULL),
    ('LUNCHBOX',   'Lunchbox',   'THEME_CORE', 'COMMON', 'DEFAULT', NULL),
    ('LIBRARY',    'Library',    'THEME_CORE', 'COMMON', 'DEFAULT', NULL),
    ('HEADMASTER', 'Headmaster', 'THEME_CORE', 'RARE',   'MYSTERY_BOX', NULL),
    ('GOLDSTAR',   'Gold Star',  'THEME_CORE', 'SECRET', 'MYSTERY_BOX', NULL)
ON CONFLICT (id) DO NOTHING;

-- Backfill: every row in character_unlocks → user_mochi. The PK on
-- user_mochi makes the INSERT idempotent so re-running the migration
-- (or running on a fresh DB) doesn't error.
INSERT INTO user_mochi (user_id, mochi_id, acquired_at, acquired_via)
SELECT user_id, character, unlocked_at,
       CASE
           WHEN character IN ('HEADMASTER', 'GOLDSTAR') THEN 'MYSTERY_BOX'
           ELSE 'DEFAULT'
       END
  FROM character_unlocks
 WHERE character IN (
       'PENCIL','SCIENCE','PE','ART','LUNCHBOX','LIBRARY','HEADMASTER','GOLDSTAR')
ON CONFLICT (user_id, mochi_id) DO NOTHING;
