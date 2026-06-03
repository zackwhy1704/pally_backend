-- V52: per-avatar active flag + per-user 24-hour slot-swap cooldown.
--
-- FREE USERS: only `freeTutorCap` slots may be ACTIVE at a time.
-- When trial expires the UI asks the user to pick which Mochis
-- stay active; the rest become inactive (locked).
-- PREMIUM: all avatars stay active regardless; the column is still
-- present so the same code path works without branching.
--
-- avatars.is_active  — TRUE = usable for chat/quiz/upload.
--                       FALSE = "locked" — visible, no interaction.
-- users.last_slot_change_at — timestamp of the last user-initiated
--   activate/deactivate; enforces the 24-hour between-swaps cooldown.

ALTER TABLE avatars
    ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE users
    ADD COLUMN last_slot_change_at TIMESTAMPTZ;

-- Index: fetching "active avatars for user" is the hot path on every
-- home-screen load, so index the compound (user_id, is_active) pair.
CREATE INDEX idx_avatars_user_active ON avatars(user_id, is_active);
