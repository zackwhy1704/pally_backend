-- Streak retention system (audit's Phase 1).
--
-- streak_freezes:  one free "save" so a single missed day doesn't reset
--                  the streak. New users start with 1; cap at 3; earn one
--                  per 7-day milestone.
-- longest_streak:  for the milestone-ladder UI and bragging rights.
-- streak_milestones_reached: CSV ("3,7,14") so we celebrate each rung
--                  exactly once even if the user re-crosses it after a
--                  reset.

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS streak_freezes INT NOT NULL DEFAULT 1;
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS longest_streak INT NOT NULL DEFAULT 0;
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS streak_milestones_reached TEXT;

-- Per-day completion log. Lets the client render a 7-dot week strip and
-- distinguishes "missed day" from "never active".
CREATE TABLE IF NOT EXISTS daily_activity_days (
    user_id VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    day     DATE        NOT NULL,
    PRIMARY KEY (user_id, day)
);

CREATE INDEX IF NOT EXISTS idx_daily_days_user
    ON daily_activity_days(user_id, day DESC);
