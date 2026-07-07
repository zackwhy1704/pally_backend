-- Teacher-removal of a captured marking correction (Part 4, the manual damper).
-- Unlike the student weakness loop, marking corrections have no self-correcting
-- mechanism, so a bad/idiosyncratic correction could drift future AI drafts. This
-- lets the teacher REMOVE a correction so it is excluded from future recompiles.
--
-- Soft-delete (removed_at) rather than a hard delete: keeps an audit trail and
-- keeps the row out of both the teacher view AND the recompile feed. NULL = active.
-- NOTE (honest semantics): removing an as-yet-UNCOMPILED correction fully prevents
-- it grounding any draft. Removing an ALREADY-COMPILED one excludes it from FUTURE
-- feeds but does not retroactively un-merge it from the marking-wiki (the compile
-- harness merges by slug + decays over time); an immediate purge would need a full
-- marking-corpus rebuild. The teacher view surfaces compiled-vs-pending so a bad
-- correction can be caught before it lands.
ALTER TABLE marking_corrections ADD COLUMN removed_at TIMESTAMP;

-- The recompile feed reads uncompiled + not-removed; index that access path.
CREATE INDEX idx_marking_corrections_feed
    ON marking_corrections (class_id, compiled_at, removed_at);
