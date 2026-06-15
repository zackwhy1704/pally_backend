-- Make every class visually distinct so kids can tell their classes apart at a
-- glance. Earlier defaults (V75) set body-hue only with accessory/aura = none,
-- which reads as "no customisation". Upgrade ONLY the pure auto-defaults (both
-- accessory and aura still 'none', or NULL) to a deterministic body + accessory
-- + aura derived from the class id. Classes a teacher has actually customised
-- (any non-none accessory or aura) are left untouched.
UPDATE org_class
SET mochi_config = json_build_object(
        'body', abs(mod(hashtext(id), 12)),
        'accessory', (ARRAY['bow','cap','glasses','crown','headband'])[1 + abs(mod(hashtext(id || 'acc'), 5))],
        'aura', (ARRAY['sparkle','fire','chill','electric','bloom'])[1 + abs(mod(hashtext(id || 'aura'), 5))]
    )::text
WHERE mochi_config IS NULL
   OR mochi_config LIKE '%"accessory":"none","aura":"none"%';
