-- V94: widen learning_module.title VARCHAR(500) → TEXT.
--
-- ModuleContentGenerator copies the title straight from its source wiki page
-- (module.setTitle(page.getTitle())). V92 made wiki_pages.title TEXT, so a long
-- LLM-generated page title now flows into this still-bounded VARCHAR(500) and can
-- overflow → DataIntegrity → the module-generation step fails. The static
-- schema_llm_column_audit flagged this as the one remaining HIGH-risk column
-- (bounded VARCHAR + LLM-derived + free-text). Policy: LLM free text → TEXT.
ALTER TABLE learning_module
    ALTER COLUMN title TYPE TEXT;
