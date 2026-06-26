-- V95: durable per-avatar compile status (C2).
--
-- CompileJobStore is an in-memory ConcurrentHashMap, so on a SECOND Railway replica
-- the web's GET /wiki/compile/status can hit the other instance, find nothing, and
-- show "✓ compiled" on a partial compile — silently reintroducing the surfacing bug.
-- This table holds the latest compile outcome per avatar so the status endpoint is
-- correct regardless of which instance serves it. The web is unchanged (it already
-- polls /wiki/compile/status).
CREATE TABLE compile_status (
    avatar_id      VARCHAR(36)  PRIMARY KEY,
    state          VARCHAR(20)  NOT NULL,
    pages_compiled INT          NOT NULL DEFAULT 0,
    pages_total    INT          NOT NULL DEFAULT 0,
    pages_failed   INT          NOT NULL DEFAULT 0,
    failed_pages   TEXT,                       -- JSON array of {slug, reason}
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
