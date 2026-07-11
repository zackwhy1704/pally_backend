-- Chapter-chunk titles come from PDF bookmarks, which can be arbitrarily long / contain a
-- whole paragraph. They are written to BOTH knowledge_files.file_name (VARCHAR(255), V1) and
-- knowledge_files.chunk_title (VARCHAR(255), V113) — a 157-page upload with a long bookmark
-- title 400'd on "value too long for varchar(255)", killing the whole segmentation.
--
-- Fix-the-FAMILY (both columns bookmark text reaches), belt-and-suspenders with the extraction
-- truncation (PdfTextExtractor.sanitizeBookmarkTitle, ~200 chars): widen both to TEXT so a
-- long title can NEVER fail the write. TEXT is unbounded; NOT NULL on file_name is preserved.
-- Same class of fix already applied to wiki slug/title (V92/V94) — its knowledge_files
-- siblings were missed until now.

ALTER TABLE knowledge_files ALTER COLUMN file_name TYPE TEXT;
ALTER TABLE knowledge_files ALTER COLUMN chunk_title TYPE TEXT;
