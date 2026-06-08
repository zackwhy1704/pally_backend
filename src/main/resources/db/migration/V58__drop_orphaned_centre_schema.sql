-- V58: Drop the 12 orphaned centre_* tables created by V56 that nothing uses.
-- The live centre model is organizations + users.centre_id (V35).
-- The richer V56 schema is preserved in git/V56 file and re-appliable when needed.
-- avatars.centre_avatar and avatars.avatar_locked are KEPT (used by app + analytics).
-- avatars.centre_enrolment_id is DROPPED (FK'd to the now-gone centre_enrolment).

-- Drop activity/leaf tables first (FK order)
DROP TABLE IF EXISTS centre_chat_message   CASCADE;
DROP TABLE IF EXISTS student_assignment    CASCADE;
DROP TABLE IF EXISTS assignment            CASCADE;
DROP TABLE IF EXISTS centre_flashcard      CASCADE;
DROP TABLE IF EXISTS centre_question       CASCADE;
DROP TABLE IF EXISTS wiki_chunk            CASCADE;
DROP TABLE IF EXISTS wiki_topic            CASCADE;
DROP TABLE IF EXISTS content_source        CASCADE;
DROP TABLE IF EXISTS centre_enrolment      CASCADE;
DROP TABLE IF EXISTS centre_class          CASCADE;
DROP TABLE IF EXISTS centre_user           CASCADE;
DROP TABLE IF EXISTS centre                CASCADE;

-- Drop the FK column that referenced the now-gone centre_enrolment table
DROP INDEX IF EXISTS idx_avatar_centre_enrolment;
ALTER TABLE avatars DROP COLUMN IF EXISTS centre_enrolment_id;
