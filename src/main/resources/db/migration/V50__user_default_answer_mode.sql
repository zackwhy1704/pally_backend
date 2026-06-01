-- Per-user default answer mode so the Guide Me / Just Answer preference
-- follows the user across devices and all their Mochis.
-- GUIDE = Socratic (never hands over the answer) — the default and recommended.
-- ANSWER = direct worked solution ("for checking your work").
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS default_answer_mode VARCHAR(10) NOT NULL DEFAULT 'GUIDE';
