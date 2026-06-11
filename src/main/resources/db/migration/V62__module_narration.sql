CREATE TABLE module_narration (
    id VARCHAR(36) PRIMARY KEY,
    module_id VARCHAR(36) NOT NULL REFERENCES learning_module(id) ON DELETE CASCADE,
    voice_id VARCHAR(100) NOT NULL DEFAULT 'default',
    segments_json TEXT NOT NULL,
    total_duration_ms INT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'GENERATING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(module_id)
);
