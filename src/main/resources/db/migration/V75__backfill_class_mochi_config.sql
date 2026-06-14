-- FIX 1: classes created before the Mochi customiser have mochi_config = NULL,
-- so the apps fall back to the plain class avatar (mochi + coloured ring) instead
-- of a customised look. Backfill a deterministic default for every NULL class so
-- it renders a distinct customised Mochi immediately. Body variant 0–11 derived
-- from a stable hash of the class id; accessory/aura default to none. Teachers can
-- still override via the picker. New classes get a default at creation time
-- (ClassController.createClass), so this only touches legacy rows.
UPDATE org_class
SET mochi_config =
    '{"body":' || abs(mod(hashtext(id), 12))::text || ',"accessory":"none","aura":"none"}'
WHERE mochi_config IS NULL;
