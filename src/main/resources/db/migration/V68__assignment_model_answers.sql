-- Part A2: teacher model answers with a server-withheld timed release.
-- model_answer is a JSON blob (object keyed by question/concept, or free text).
-- answers_released_at gates server-side disclosure: until it is <= now() the
-- student-facing mapper omits model answers entirely.

ALTER TABLE assignment ADD COLUMN model_answer TEXT;
ALTER TABLE assignment ADD COLUMN answers_released_at TIMESTAMPTZ;
