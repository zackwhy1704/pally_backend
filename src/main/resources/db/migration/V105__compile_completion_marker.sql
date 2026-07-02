-- Item 1 (per-file compile resume / silent partial-loss fix).
--
-- The incremental-compile skip used to treat a file as "already compiled" the
-- moment it had ANY wiki_page_sources row. With within-file segmentation, a file
-- whose compile fails mid-run (e.g. segment 3 of 5) has rows for segments 1-2, so
-- it looked "done" and was skipped forever — segments 3-5 silently lost.
--
-- Fix: the skip now keys on knowledge_files.compiled_by (set ONLY when every
-- segment of a file has compiled). A mid-run failure leaves compiled_by NULL, so
-- the next compile re-runs the file (idempotent — pages merge by slug) instead of
-- skipping it.
--
-- Backfill: every file that already produced wiki pages counts as complete, so
-- switching the skip does NOT trigger a mass recompile of existing corpora.
UPDATE knowledge_files
SET compiled_by = 'legacy'
WHERE compiled_by IS NULL
  AND id IN (SELECT DISTINCT knowledge_file_id FROM wiki_page_sources);
