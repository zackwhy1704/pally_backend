-- Assignment system
CREATE TABLE assignment (
    id VARCHAR(36) PRIMARY KEY,
    class_id VARCHAR(36) NOT NULL REFERENCES org_class(id) ON DELETE CASCADE,
    title VARCHAR(500) NOT NULL,
    type VARCHAR(20) NOT NULL,
    module_ids TEXT,
    item_ids TEXT,
    stages TEXT,
    mastery_threshold DECIMAL(5,2),
    due_date TIMESTAMPTZ NOT NULL,
    created_by VARCHAR(36) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_assignment_class ON assignment(class_id);

CREATE TABLE assignment_completion (
    id VARCHAR(36) PRIMARY KEY,
    assignment_id VARCHAR(36) NOT NULL REFERENCES assignment(id) ON DELETE CASCADE,
    user_id VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    score_summary_json TEXT,
    UNIQUE(assignment_id, user_id)
);
CREATE INDEX idx_completion_user ON assignment_completion(user_id);
CREATE INDEX idx_completion_assignment ON assignment_completion(assignment_id);

-- Content publishing lifecycle
ALTER TABLE module_content_item
    ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'LIVE';

-- Content gap signals
CREATE TABLE content_gap_signal (
    id VARCHAR(36) PRIMARY KEY,
    class_id VARCHAR(36),
    user_id VARCHAR(36),
    query_text VARCHAR(1000) NOT NULL,
    matched_confidence DECIMAL(3,2),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_gap_class ON content_gap_signal(class_id);
