-- Grade integrity, exposure parity: the answer key now also holds the question's
-- explanation, so a teacher-graded quiz can WITHHOLD it from the served question
-- (it reveals the answer just like correctIndex does) and return it POST-submit
-- in the feedback instead. Nullable: legacy keys + questions without an
-- explanation simply have none.
ALTER TABLE quiz_answer_keys ADD COLUMN explanation TEXT;
