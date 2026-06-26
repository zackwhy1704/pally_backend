-- V93: widen wiki_pages.conflict_note VARCHAR(500) → TEXT.
--
-- conflict_note (V73) holds an LLM-generated free-text reason explaining why two
-- compiled passages disagree. It was capped at 500 chars via a raw Java
-- String.substring(0, 500) — which counts UTF-16 units and can split a surrogate
-- pair at the boundary, producing an unpaired surrogate. That string is invalid
-- UTF-8, so the JDBC write fails with an encoding error → DataIntegrity → 400.
-- Because it depends on the exact note content/length, it surfaced intermittently
-- on MULTI-DOC compiles (the only path that triggers conflict detection).
--
-- Free-text explanations have no business being length-capped. Make it TEXT; the
-- raw-substring clamp is removed in code (V92 already did title→TEXT, slug→160).
ALTER TABLE wiki_pages
    ALTER COLUMN conflict_note TYPE TEXT;
