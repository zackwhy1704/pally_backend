-- Marking references: the teacher BRAIN moat. A teacher uploads their OWN past
-- MARKED papers (exemplars across grade bands), rubrics / mark schemes, and
-- marking guidelines. We store the originals and extract their text so the AI
-- homework-feedback DRAFT is grounded in THAT teacher's standard instead of a
-- generic syllabus. Read by the homework feedback generator via
-- MarkingReferenceContextPort; teacher-in-the-loop is unchanged (AI still only
-- ever drafts).
CREATE TABLE marking_reference (
    id              VARCHAR(36)  PRIMARY KEY,
    class_id        VARCHAR(36)  NOT NULL,
    -- MARKED_PAPER | RUBRIC | GUIDELINE
    kind            VARCHAR(32)  NOT NULL,
    title           VARCHAR(300) NOT NULL,
    -- Teacher's annotation, e.g. "A-grade exemplar" / "full working shown".
    note            TEXT,
    -- JSON array of {key,name,contentType,size} — the stored artifact(s).
    files_json      TEXT         NOT NULL,
    -- OCR / PDF / text extraction of the reference (best-effort; may be empty
    -- when unreadable — the artifact is still viewable and still counts as
    -- guidance the teacher chose to add). This is the grounding payload.
    extracted_text  TEXT,
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL
);

-- List a class's references, newest first.
CREATE INDEX idx_marking_reference_class ON marking_reference (class_id, created_at);
