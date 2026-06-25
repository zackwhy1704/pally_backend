-- V92: stop two DataIntegrity 500s/400s the centre eval surfaced.
--
-- 1) assignments.due_date was NOT NULL, but the service was changed to make a
--    deadline OPTIONAL (an assignment with no due date is valid). Saving a
--    no-deadline assignment violated the NOT NULL constraint → DataIntegrity →
--    assignment creation failed. Drop the constraint to match the domain rule.
ALTER TABLE assignment
    ALTER COLUMN due_date DROP NOT NULL;

-- 2) wiki_pages.slug / title come straight from the LLM compile draft and so are
--    unbounded — a long generated title/slug overflowed VARCHAR(100)/VARCHAR(255)
--    and failed the whole wiki compile (intermittent, content-dependent). Titles
--    have no business being capped → TEXT; slug keeps a bounded identifier but
--    with real headroom (code also clamps defensively).
ALTER TABLE wiki_pages
    ALTER COLUMN title TYPE TEXT;
ALTER TABLE wiki_pages
    ALTER COLUMN slug TYPE VARCHAR(160);
