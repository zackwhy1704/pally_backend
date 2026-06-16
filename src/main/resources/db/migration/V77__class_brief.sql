-- Cached AI-generated class brief per (class, module).
-- Invalidated when newer module_progress.completed_at exists.
CREATE TABLE IF NOT EXISTS class_brief (
    id             VARCHAR(36)  NOT NULL PRIMARY KEY,
    class_id       VARCHAR(36)  NOT NULL,
    module_id      VARCHAR(36),                    -- NULL = whole-class scope
    brief_json     TEXT         NOT NULL,
    generated_at   TIMESTAMPTZ  NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_class_brief_class_module
    ON class_brief (class_id, module_id);
