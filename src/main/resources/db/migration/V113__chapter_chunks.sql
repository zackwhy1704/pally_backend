-- Chapter-chunked compile: a large uploaded file (>50k extracted chars) is split
-- into child KnowledgeFile rows ("chunks"), each a compilable unit the student PICKS.
-- Children live BENEATH the existing machinery (compiled_by, dedup, scheduler) as
-- ordinary rows; only these columns are new.
--
-- Parent state = SEGMENTED (compile-ignored, holds the full extracted text).
-- Child state  = PENDING_CHUNK until picked, then READY (swept by the normal compile).
-- Neither new status is READY, so neither is touched by executeBatched's
-- compiled_by-NULL+READY sweep nor by findAvatarIdsNeedingRecompile's stale-detection.

ALTER TABLE knowledge_files
    ADD COLUMN IF NOT EXISTS parent_file_id VARCHAR(36)
        REFERENCES knowledge_files(id) ON DELETE CASCADE;

ALTER TABLE knowledge_files ADD COLUMN IF NOT EXISTS page_from   INT;
ALTER TABLE knowledge_files ADD COLUMN IF NOT EXISTS page_to     INT;
ALTER TABLE knowledge_files ADD COLUMN IF NOT EXISTS chunk_title VARCHAR(255);

-- compiled_at stamps WHEN compiled_by was set — the rolling-30d anchor for the
-- chunk-compile allowance (success-based: a chunk counts against quota only once
-- it actually compiled, so a failed compile never burns allowance). Existing
-- already-compiled files keep compiled_at NULL and count zero (grandfather-zero).
ALTER TABLE knowledge_files ADD COLUMN IF NOT EXISTS compiled_at TIMESTAMPTZ;

-- Fast child lookup (picker/locked-chapter list) and sibling counts.
CREATE INDEX IF NOT EXISTS idx_kf_parent ON knowledge_files(parent_file_id);

-- Chunk-compile allowance count: children (parent_file_id NOT NULL) that
-- successfully compiled (compiled_at NOT NULL) in a user's rolling window.
CREATE INDEX IF NOT EXISTS idx_kf_chunk_compiled
    ON knowledge_files(user_id, compiled_at)
    WHERE parent_file_id IS NOT NULL AND compiled_at IS NOT NULL;
