-- Pre-class adaptive diagnostic (Phase 2). topic_scope already holds the NEW
-- topic's primer slugs; prereq_scope holds the PRIOR-topic wiki slugs the
-- teacher picks to diagnose. Each student's pre-class set = uniform primer
-- (new topic) + a diagnostic targeting THEIR weak prerequisites.
ALTER TABLE assignment ADD COLUMN prereq_scope TEXT;
