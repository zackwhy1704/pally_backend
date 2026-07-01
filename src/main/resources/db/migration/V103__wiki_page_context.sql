-- Contextual chunk summary per wiki page (student notes AND marking pages): a
-- 1-2 sentence "this page covers X within Subject/Topic Y" the compiler emits and
-- we prepend to the page's grounding/retrieval text. Research-backed: the single
-- highest-impact cheap retrieval lift — it disambiguates a page for the model
-- even when the query is terse. Nullable; older pages simply have no context
-- until their next recompile.
ALTER TABLE wiki_pages ADD COLUMN context TEXT;
