-- V60: AI pipeline hardening — track which OCR engine and compiler served each file
ALTER TABLE knowledge_files ADD COLUMN ocr_engine VARCHAR(30);
ALTER TABLE knowledge_files ADD COLUMN compiled_by VARCHAR(30);
