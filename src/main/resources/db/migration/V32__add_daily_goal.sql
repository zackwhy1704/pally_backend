-- Daily goal ring (audit's Phase 2). Replaces the dead weekMinutes chart
-- with an "Apple Fitness ring" the kid can close every day.
--
-- goal_type is QUIZ | XP | MINUTES; goal_target is interpreted in the
-- matching unit (e.g. QUIZ + target=1 means "complete one daily quiz").
-- Defaults are deliberately easy so new users see a "you can do this"
-- ring on day one.

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS daily_goal_type VARCHAR(20)
        NOT NULL DEFAULT 'QUIZ';
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS daily_goal_target INT
        NOT NULL DEFAULT 1;
