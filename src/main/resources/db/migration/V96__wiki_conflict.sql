-- V96: teacher-facing fact-conflict queue (Part A).
--
-- detectConflict reliably flags numeric/date/entity contradictions. v1 keeps the
-- newest value LIVE for students (no quarantine) and routes every detected conflict
-- to the teacher for resolution. A teacher's resolution is durable: the page is
-- "locked" (a RESOLVED row for the slug), so a later recompile that WOULD change it
-- opens a NEW conflict instead of silently overwriting.
CREATE TABLE wiki_conflict (
    id              VARCHAR(36)  PRIMARY KEY,
    avatar_id       VARCHAR(36)  NOT NULL,
    slug            VARCHAR(160) NOT NULL,
    old_value       TEXT,                       -- existing page content (excerpt)
    new_value       TEXT,                       -- incoming draft content (excerpt)
    note            TEXT,                       -- concrete clash, e.g. "...atp: 38 vs 36"
    confidence      VARCHAR(20)  NOT NULL,      -- DETERMINISTIC | PROSE
    status          VARCHAR(20)  NOT NULL DEFAULT 'OPEN',  -- OPEN | RESOLVED
    canonical_value TEXT,                       -- teacher-chosen content on resolve
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_at     TIMESTAMPTZ,
    resolved_by     VARCHAR(36)
);
CREATE INDEX idx_wiki_conflict_avatar_status ON wiki_conflict(avatar_id, status);
CREATE INDEX idx_wiki_conflict_slug ON wiki_conflict(avatar_id, slug, status);
