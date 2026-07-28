-- Content-language architecture (additive only). Two INDEPENDENT axes, never conflated:
--   * users.preferred_locale   — the UI-chrome language the user chose ('en' | 'zh').
--   * avatars.content_language — the language the AI generates that avatar's content in.
-- A child with an English phone UI can be in a 华文 class and vice versa, so these are
-- separate columns on separate tables. The artifact tables (wiki_pages, learning_module,
-- flashcards) also carry content_language so a generated artifact records the language it
-- was produced in and clients can render it appropriately.
--
-- All columns NOT NULL DEFAULT 'en': on Postgres 11+ a non-volatile column default is
-- filled as metadata (no table rewrite), so every existing row backfills to 'en' atomically
-- in the ADD COLUMN — no separate UPDATE, and no row is ever null. Purely additive: no drops,
-- no type changes, no changes to existing columns. IF NOT EXISTS keeps it re-run safe (house
-- style, see V25/V50/V120).

-- (a) Per-user preferred UI/chrome locale.
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS preferred_locale VARCHAR(10) NOT NULL DEFAULT 'en';

-- (b) Per-avatar content language (the language its brain/notes/lessons are generated in).
ALTER TABLE avatars
    ADD COLUMN IF NOT EXISTS content_language VARCHAR(10) NOT NULL DEFAULT 'en';

-- (c) Language tag on generated artifacts (records the language each was produced in).
ALTER TABLE wiki_pages
    ADD COLUMN IF NOT EXISTS content_language VARCHAR(10) NOT NULL DEFAULT 'en';
ALTER TABLE learning_module
    ADD COLUMN IF NOT EXISTS content_language VARCHAR(10) NOT NULL DEFAULT 'en';
ALTER TABLE flashcards
    ADD COLUMN IF NOT EXISTS content_language VARCHAR(10) NOT NULL DEFAULT 'en';
