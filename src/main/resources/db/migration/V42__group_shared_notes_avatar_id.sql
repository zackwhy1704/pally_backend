-- Attach avatar_id to shared notes so the client can deep-link to the
-- wiki viewer for that tutor. NULL for notes shared before this migration.
ALTER TABLE group_shared_notes
    ADD COLUMN IF NOT EXISTS avatar_id VARCHAR(36);
