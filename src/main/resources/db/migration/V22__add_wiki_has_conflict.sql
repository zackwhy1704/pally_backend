-- Adds has_conflict flag to wiki_pages so the wiki compiler can mark a page
-- when an incoming upload meaningfully contradicts the stored version on the
-- same slug. The UI uses this to surface a small warning on the page.
ALTER TABLE wiki_pages
    ADD COLUMN IF NOT EXISTS has_conflict BOOLEAN NOT NULL DEFAULT FALSE;
