-- Per-student differentiated assignments. `personalized` makes one teacher
-- action resolve to a per-student targeted module set at start time;
-- `topic_scope` (comma-separated wiki slugs) bounds what the assignment covers
-- (null = whole class). The resolved set is snapshotted per student on the
-- completion when they begin. Table names are singular (assignment /
-- assignment_completion), matching the existing @Table mappings.
ALTER TABLE assignment ADD COLUMN personalized BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE assignment ADD COLUMN topic_scope TEXT;

ALTER TABLE assignment_completion ADD COLUMN resolved_module_ids TEXT;
ALTER TABLE assignment_completion ADD COLUMN resolved_at TIMESTAMPTZ;
