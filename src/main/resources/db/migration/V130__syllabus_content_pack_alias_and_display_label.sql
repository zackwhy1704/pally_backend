-- V130: Phase 2 support for syllabus_content_pack.
--
-- 1. display_label: a client-safe, human-readable title (e.g. "Algorithms &
--    Problem-Solving") separate from the internal syllabus_code/topic_tag. Closes a
--    Phase 1 leak: the browse endpoint's response previously exposed the raw internal
--    tags (e.g. "SG-G3-COMPUTING-7155") to any authenticated client, which violates the
--    "backend-only, never shown in user-facing copy" requirement even though the exact
--    strings aren't literally "MOE"/"SEAB". No backfill needed — Phase 1 shipped the
--    mechanism only, zero packs have ever been created (resolveOrCreatePack has never
--    been called outside a unit test), so no existing row needs a real label. NOT NULL
--    with an empty-string default is a safety net only; the service layer requires a
--    real (non-blank) label going forward.
--
-- 2. syllabus_content_pack_alias: lets one pack (one avatar, one set of generated
--    modules) be discoverable under a SECOND syllabus's topic tag without duplicating
--    content — e.g. a G3 Computing "Abstraction-and-Algorithms" pack also tagged for
--    Cambridge IGCSE's "Algorithm-Design-and-Problem-Solving". Purely additive: the
--    pack's own (syllabus_code, topic_tag) columns and unique constraint from V129 are
--    untouched, so every Phase 1 code path keeps working unmodified.
ALTER TABLE syllabus_content_pack
    ADD COLUMN display_label VARCHAR(200) NOT NULL DEFAULT '';

CREATE TABLE syllabus_content_pack_alias (
    id            VARCHAR(36)  PRIMARY KEY,
    pack_id       VARCHAR(36)  NOT NULL REFERENCES syllabus_content_pack(id) ON DELETE CASCADE,
    syllabus_code VARCHAR(64)  NOT NULL,
    topic_tag     VARCHAR(100) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    UNIQUE (syllabus_code, topic_tag)
);

CREATE INDEX idx_syllabus_content_pack_alias_pack ON syllabus_content_pack_alias(pack_id);
