-- V51: Wiki provenance table + brain_state column on avatars
-- brain_state: tracks whether the avatar's wiki is READY, PENDING_RECOMPILE, or COMPILING
ALTER TABLE avatars ADD COLUMN brain_state VARCHAR(20) NOT NULL DEFAULT 'READY';

-- wiki_page_sources: links each wiki page to the knowledge files that contributed to it
CREATE TABLE wiki_page_sources (
    wiki_page_id      VARCHAR(36) NOT NULL REFERENCES wiki_pages(id)       ON DELETE CASCADE,
    knowledge_file_id VARCHAR(36) NOT NULL REFERENCES knowledge_files(id)  ON DELETE CASCADE,
    PRIMARY KEY (wiki_page_id, knowledge_file_id)
);

CREATE INDEX idx_wiki_page_sources_page ON wiki_page_sources(wiki_page_id);
CREATE INDEX idx_wiki_page_sources_file ON wiki_page_sources(knowledge_file_id);
