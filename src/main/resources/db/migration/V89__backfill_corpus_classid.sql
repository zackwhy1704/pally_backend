-- Backfill for the corpus-avatar classId orphaning bug (Bug #3).
-- createClass historically never set the corpus avatar's class_id, so every
-- module generated from a class corpus was tagged class_id = NULL and
-- classModules() returned nothing. The code fix sets it for new classes; this
-- repairs existing data. Table names are singular (org_class / learning_module),
-- matching the @Table mappings.

-- 1) Point each class's corpus avatar at its class.
UPDATE avatars a
SET class_id = c.id
FROM org_class c
WHERE c.corpus_avatar_id = a.id
  AND a.class_id IS NULL;

-- 2) Re-tag already-generated orphaned modules (avatar is a class corpus).
UPDATE learning_module m
SET class_id = c.id
FROM org_class c
WHERE m.avatar_id = c.corpus_avatar_id
  AND m.class_id IS NULL;
