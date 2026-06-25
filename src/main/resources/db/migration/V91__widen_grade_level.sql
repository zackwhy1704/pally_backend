-- V91: widen avatars.grade_level from VARCHAR(10) → VARCHAR(50).
--
-- BUG: VARCHAR(10) overflowed on common grade labels — "Secondary 3" is 11 chars.
-- Both POST /avatars (with gradeLevel) and POST /onboard/quick (which maps the
-- onboarding `level` straight into gradeLevel) threw a SQL "value too long" error,
-- surfaced as a generic 500, blocking avatar/content creation entirely.
ALTER TABLE avatars
    ALTER COLUMN grade_level TYPE VARCHAR(50);
