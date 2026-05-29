-- Flag rows that came from the built-in starter pack so the UI can show a
-- "Sample" badge and the compiler can prefer the user's real uploads when
-- they conflict. Existing rows default to FALSE (user-authored).
ALTER TABLE wiki_pages
    ADD COLUMN IF NOT EXISTS is_seed BOOLEAN NOT NULL DEFAULT FALSE;
