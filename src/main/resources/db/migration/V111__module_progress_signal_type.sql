-- Trust-typing of grading signals (grading-harness hardening).
-- signal_type distinguishes how a module_progress row was graded:
--   DETERMINISTIC — server graded a raw choice vs the persisted key (full weight)
--   SELF_REPORT   — student self-assessed an open-ended answer (low weight)
--   UNGRADED      — no trustworthy signal (eval error/skip/legacy/malformed);
--                   contributes nothing to mastery. UNGRADED rows carry score=NULL
--                   so they are distinguishable from a genuine 0.0 (a false fail).
-- Existing rows stay NULL = legacy: the mastery reader treats NULL as full weight
-- to preserve current students' mastery (no silent recompute on deploy).
ALTER TABLE module_progress ADD COLUMN signal_type VARCHAR(16);
