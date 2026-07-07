-- Marking corrections: the teacher-in-the-loop WRITE signal for the marking
-- feedback loop. When a teacher RELEASES homework feedback whose grade/feedback
-- materially DIFFERS from the AI's draft, we capture the delta here — "the AI
-- said X, the teacher corrected to Y". This is the marking analogue of the
-- student weakness signal: a cheap, immediate write captured at release, later
-- compiled (debounced) into the marking-wiki so future AI drafts adapt to how
-- THIS teacher actually marks.
--
-- Captured ONLY for a SUBSTANTIVE delta (a real grade change or feedback that
-- redirects rather than embellishes) — cosmetic edits are not corrections and
-- would only add noise. compiled_at is NULL until Part 3 ingests it into the
-- marking-wiki; it is set once the correction has grounded a recompile.
CREATE TABLE marking_corrections (
    id                  VARCHAR(36) PRIMARY KEY,
    submission_id       VARCHAR(36) NOT NULL,
    class_id            VARCHAR(36) NOT NULL,
    subject             VARCHAR(64),
    -- What the AI suggested (parsed from the release-time draft).
    ai_suggested_grade  VARCHAR(64),
    ai_feedback         TEXT,
    -- What the teacher actually released.
    teacher_grade       VARCHAR(64),
    teacher_feedback    TEXT,
    captured_at         TIMESTAMP    NOT NULL,
    -- NULL = not yet compiled into the marking-wiki (Part 3 picks these up).
    compiled_at         TIMESTAMP
);

-- List a class's corrections, newest first (teacher visibility, Part 4).
CREATE INDEX idx_marking_corrections_class ON marking_corrections (class_id, captured_at);
-- Find uncompiled corrections for the debounced recompile (Part 3).
CREATE INDEX idx_marking_corrections_uncompiled ON marking_corrections (class_id, compiled_at);
