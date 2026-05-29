-- Reward-system tuning round: a new starter "Mochi" replaces the 6 free
-- defaults as the only DEFAULT-acquisition character. All 8 originals
-- become MYSTERY_BOX pulls with explicit COMMON/RARE/SECRET odds — see
-- the per-row star_cost field which we repurpose here as the weight
-- denominator basis-point (kept in code instead so artists can read it).
--
-- IMPORTANT — existing unlock behaviour preserved:
--   - Anyone who ALREADY had PENCIL/SCIENCE/PE/ART/LUNCHBOX/LIBRARY via
--     character_unlocks (the V40 backfill) keeps them. We do not delete
--     from user_mochi or character_unlocks.
--   - New users will start with just MOCHI (the seed) instead of the 6.

-- The new starter character. We give it a fresh id ("MOCHI") that does
-- NOT collide with any existing character_unlocks row.
INSERT INTO mochi_characters (id, name, theme_id, rarity, acquisition, star_cost)
VALUES ('MOCHI', 'Mochi', 'THEME_CORE', 'COMMON', 'DEFAULT', NULL)
ON CONFLICT (id) DO UPDATE SET
    rarity = EXCLUDED.rarity,
    acquisition = EXCLUDED.acquisition,
    name = EXCLUDED.name;

-- Flip the 6 originals from DEFAULT to MYSTERY_BOX/COMMON. Rarity stays
-- COMMON so they're picked from the 6×15% = 90% common bucket.
UPDATE mochi_characters
   SET acquisition = 'MYSTERY_BOX', rarity = 'COMMON'
 WHERE id IN ('PENCIL', 'SCIENCE', 'PE', 'ART', 'LUNCHBOX', 'LIBRARY');

-- Confirm the other two are still in the correct bucket (idempotent).
UPDATE mochi_characters SET rarity = 'RARE'
 WHERE id = 'HEADMASTER';
UPDATE mochi_characters SET rarity = 'SECRET'
 WHERE id = 'GOLDSTAR';
