-- Pally initial schema
-- V1: Create core tables for avatars, knowledge files, wiki pages, and chat messages

CREATE TABLE avatars (
    id              VARCHAR(36)  PRIMARY KEY,
    user_id         VARCHAR(36)  NOT NULL,
    name            VARCHAR(50)  NOT NULL,
    subject         VARCHAR(20)  NOT NULL,
    character_type  VARCHAR(20)  NOT NULL,
    wiki_page_count INT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL
);

CREATE TABLE knowledge_files (
    id           VARCHAR(36)   PRIMARY KEY,
    avatar_id    VARCHAR(36)   NOT NULL REFERENCES avatars(id) ON DELETE CASCADE,
    user_id      VARCHAR(36)   NOT NULL,
    file_name    VARCHAR(255)  NOT NULL,
    storage_key  VARCHAR(500)  NOT NULL,
    page_count   INT           NOT NULL DEFAULT 0,
    upload_type  VARCHAR(20)   NOT NULL,
    status       VARCHAR(20)   NOT NULL DEFAULT 'PROCESSING',
    created_at   TIMESTAMPTZ   NOT NULL
);

CREATE TABLE wiki_pages (
    id           VARCHAR(36)   PRIMARY KEY,
    avatar_id    VARCHAR(36)   NOT NULL REFERENCES avatars(id) ON DELETE CASCADE,
    slug         VARCHAR(100)  NOT NULL,
    title        VARCHAR(255)  NOT NULL,
    content      TEXT          NOT NULL,
    certainty    VARCHAR(20)   NOT NULL DEFAULT 'INFERRED',
    updated_at   TIMESTAMPTZ   NOT NULL,
    UNIQUE (avatar_id, slug)
);

CREATE TABLE chat_messages (
    id           VARCHAR(36)   PRIMARY KEY,
    avatar_id    VARCHAR(36)   NOT NULL REFERENCES avatars(id) ON DELETE CASCADE,
    user_id      VARCHAR(36)   NOT NULL,
    role         VARCHAR(10)   NOT NULL,
    content      TEXT          NOT NULL,
    source_file  VARCHAR(255),
    created_at   TIMESTAMPTZ   NOT NULL
);

CREATE INDEX idx_avatars_user_id     ON avatars(user_id);
CREATE INDEX idx_knowledge_avatar_id ON knowledge_files(avatar_id);
CREATE INDEX idx_wiki_avatar_id      ON wiki_pages(avatar_id);
CREATE INDEX idx_chat_avatar_id      ON chat_messages(avatar_id, created_at DESC);
