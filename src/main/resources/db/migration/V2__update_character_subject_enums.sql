-- Migrate v1 character_type values to v2 names
UPDATE avatars SET character_type = 'ZAP'   WHERE character_type = 'ROBOT';
UPDATE avatars SET character_type = 'FINN'  WHERE character_type = 'FOX';
UPDATE avatars SET character_type = 'MOCHI' WHERE character_type = 'PANDA';
UPDATE avatars SET character_type = 'NORI'  WHERE character_type = 'OWL';
UPDATE avatars SET character_type = 'BYTE'  WHERE character_type = 'DRAGON';
UPDATE avatars SET character_type = 'BOBA'  WHERE character_type = 'OCTOPUS';

-- New subjects added in v2 (no migration needed — no rows to update)
-- GEOGRAPHY, LANGUAGES, MUSIC are new additions
