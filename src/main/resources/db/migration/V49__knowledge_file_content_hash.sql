-- SHA-256 of the normalised extracted text (lowercase, collapsed whitespace).
-- Used for exact-duplicate detection without re-running OCR or calling Claude.
-- NULL for files uploaded before this migration; they're re-indexed lazily.
ALTER TABLE knowledge_files
    ADD COLUMN IF NOT EXISTS content_hash VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_kf_avatar_hash
    ON knowledge_files(avatar_id, content_hash)
    WHERE content_hash IS NOT NULL;
