-- V65: Avatar kind split — separate centre class avatars from personal collectibles.
--
-- Centres need one avatar per class (unbounded) without polluting the student
-- collectible economy (the 9 classic + future ATW characters). We tag every
-- avatar with a `kind`:
--   PERSONAL     — a student's own collectible tutor (the default for all
--                  existing rows and every student-created avatar).
--   CENTRE_CLASS — a class-bound avatar (the hidden class corpus avatar, and
--                  the branded closed-book avatars provisioned per student on
--                  class assignment). These wear a server-derived "class
--                  uniform" appearance and are never granted, sold, or counted
--                  by the shop / collection / mystery-box economy.
--
-- `class_id` already exists on avatars from V59, so it is NOT re-added here.

ALTER TABLE avatars
    ADD COLUMN kind VARCHAR(20) NOT NULL DEFAULT 'PERSONAL';

-- Backfill: every centre-provisioned avatar (V59 flag) becomes CENTRE_CLASS so
-- existing class corpus + student avatars are correctly excluded from the
-- economy and rendered with the class uniform. All other existing rows stay
-- PERSONAL via the column default.
UPDATE avatars SET kind = 'CENTRE_CLASS' WHERE centre_avatar = TRUE;

CREATE INDEX IF NOT EXISTS idx_avatars_kind ON avatars(user_id, kind);
CREATE INDEX IF NOT EXISTS idx_avatars_class ON avatars(class_id);
