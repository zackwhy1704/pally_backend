-- Homework submissions: a student (or a teacher on their behalf) uploads the
-- student's OWN work (photo/PDF/text) to a centre class. The teacher receives
-- it, generates an AI FIRST-PASS feedback draft against the class brain, edits
-- it, and RELEASES it to the student. Teacher-in-the-loop always — nothing
-- reaches the student until released (status = RELEASED). Mirrors the Ren / MOE
-- "AI drafts, teacher approves" pattern.
CREATE TABLE homework_submission (
    id                      VARCHAR(36)  PRIMARY KEY,
    class_id                VARCHAR(36)  NOT NULL,
    -- The student the work belongs to. Set to the submitter on the student path;
    -- set to the chosen roster student when a teacher uploads physical work.
    student_id              VARCHAR(36),
    title                   VARCHAR(300) NOT NULL,
    subject                 VARCHAR(120),
    -- JSON array of {key,name,contentType,size} — the stored artifact(s).
    files_json              TEXT         NOT NULL,
    -- OCR / PDF / text extraction of the work (best-effort; may be empty when the
    -- artifact is unreadable — the teacher can still view the file and mark by hand).
    extracted_text          TEXT,
    -- SUBMITTED -> AI_DRAFTED -> TEACHER_REVIEWING -> RELEASED (+ RETURNED for redo)
    status                  VARCHAR(32)  NOT NULL,
    -- AI first-pass draft (criteria + suggestedGrade + feedback). NEVER shown to
    -- the student; a teacher-only convenience pre-fill for the editable fields.
    ai_draft_feedback_json  TEXT,
    ai_drafted_at           TIMESTAMP,
    -- The teacher's final, editable feedback + grade. Only these reach the student,
    -- and only once released.
    teacher_feedback        TEXT,
    teacher_grade           VARCHAR(60),
    released_at             TIMESTAMP,
    created_at              TIMESTAMP    NOT NULL,
    updated_at              TIMESTAMP    NOT NULL
);

-- Teacher inbox: list a class's submissions, filter by status, newest first.
CREATE INDEX idx_homework_submission_class ON homework_submission (class_id, status);
-- Student view: list my submissions for a class.
CREATE INDEX idx_homework_submission_student ON homework_submission (student_id, class_id);
