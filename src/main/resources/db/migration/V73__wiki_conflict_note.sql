-- Brain-map conflict note: a short human-readable explanation of WHY a wiki
-- page is flagged as conflicting (e.g. "boils at 100C here but 90C on the
-- volcanoes page"). Nullable; the extraction logic that populates it ships in
-- a later wave — this migration only adds the column so the DTO/entity can
-- carry it through now.
ALTER TABLE wiki_pages ADD COLUMN conflict_note VARCHAR(500);
