-- Group moderation (audit's Batch F).
--
--   relevance_status drives the shared-note badge in the group feed:
--     OK       — score >= 0.45 OR no check ran (legacy rows).
--     WARNING  — 0.20 <= score < 0.45; visible with a "Off topic?" badge.
--     BLOCKED  — score < 0.20; hidden from the feed; only the sharer can see.
--
--   group_reports captures member-driven flags so an owner can review and
--   then optionally call DELETE /groups/{id}/members/{userId} to kick.

ALTER TABLE group_shared_notes
    ADD COLUMN IF NOT EXISTS relevance_status VARCHAR(20) NOT NULL DEFAULT 'OK';
ALTER TABLE group_shared_notes
    ADD COLUMN IF NOT EXISTS relevance_score REAL;
ALTER TABLE group_shared_notes
    ADD COLUMN IF NOT EXISTS relevance_reason TEXT;

CREATE TABLE IF NOT EXISTS group_reports (
    id                 VARCHAR(36) PRIMARY KEY,
    group_id           VARCHAR(36) NOT NULL,
    reporter_user_id   VARCHAR(36) NOT NULL,
    target_user_id     VARCHAR(36),
    target_note_id     VARCHAR(36),
    reason             VARCHAR(50) NOT NULL,
    details            TEXT,
    status             VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_at        TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_group_reports_group
    ON group_reports(group_id);
CREATE INDEX IF NOT EXISTS idx_group_reports_status
    ON group_reports(group_id, status);
