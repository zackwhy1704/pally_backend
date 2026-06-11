-- Learning module pipeline: LEARN → TEST → PROVE → COMPLETE
-- Each module wraps a wiki page and provides structured content per stage.

CREATE TABLE learning_module (
    id VARCHAR(36) PRIMARY KEY,
    avatar_id VARCHAR(36) NOT NULL REFERENCES avatars(id) ON DELETE CASCADE,
    class_id VARCHAR(36),
    wiki_page_slug VARCHAR(255) NOT NULL,
    title VARCHAR(500) NOT NULL,
    stage VARCHAR(20) NOT NULL DEFAULT 'LEARN',
    tier VARCHAR(20) NOT NULL DEFAULT 'FREE',
    mastery_pct DECIMAL(5,2) DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ,
    UNIQUE(avatar_id, wiki_page_slug)
);
CREATE INDEX idx_module_avatar ON learning_module(avatar_id);

CREATE TABLE module_content_item (
    id VARCHAR(36) PRIMARY KEY,
    module_id VARCHAR(36) NOT NULL REFERENCES learning_module(id) ON DELETE CASCADE,
    stage VARCHAR(20) NOT NULL,
    type VARCHAR(30) NOT NULL,
    content_json TEXT NOT NULL,
    answer_json TEXT,
    sort_order INT NOT NULL DEFAULT 0,
    tier_required VARCHAR(20) NOT NULL DEFAULT 'FREE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_item_module ON module_content_item(module_id);
CREATE INDEX idx_item_stage ON module_content_item(module_id, stage);

CREATE TABLE module_progress (
    id VARCHAR(36) PRIMARY KEY,
    module_id VARCHAR(36) NOT NULL REFERENCES learning_module(id) ON DELETE CASCADE,
    user_id VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    item_id VARCHAR(36) NOT NULL REFERENCES module_content_item(id) ON DELETE CASCADE,
    stage VARCHAR(20) NOT NULL,
    response_json TEXT,
    score DECIMAL(5,2),
    target_concept VARCHAR(255),
    completed_at TIMESTAMPTZ,
    UNIQUE(module_id, user_id, item_id)
);
CREATE INDEX idx_progress_user_module ON module_progress(user_id, module_id);
