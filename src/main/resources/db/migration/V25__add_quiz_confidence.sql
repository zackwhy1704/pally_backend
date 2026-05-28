-- Adds per-question self-reported confidence so the quiz can flip from a pure
-- score generator into a metacognitive diagnostic (mastered / misconception /
-- lucky guess / known gap). Backwards compatible: NULL = legacy quiz with no
-- confidence captured.
ALTER TABLE quiz_question_results
    ADD COLUMN IF NOT EXISTS confidence VARCHAR(10);

CREATE INDEX IF NOT EXISTS idx_qqr_confidence
    ON quiz_question_results(user_id, confidence)
    WHERE confidence IS NOT NULL;
