-- Unified learning-outcome event stream. Additive only: existing writers
-- (module_progress, quiz_question_results, flashcards) keep their own tables
-- and consumers unchanged; each writer ALSO inserts one row here so a future
-- rewards/classroom-results layer can subscribe once instead of integrating
-- with every generator. provenance distinguishes trust tier:
--   VERIFIED_SERVER_GRADED — server-key/answer-key graded (quiz, module HOT_TAKE)
--   SPACED_VERIFIED_RECALL — SM-2 flashcard rating
--   SELF_REPORT            — student self-assessment (module PROVE/SPOT_MISTAKE)
-- No row is written for an UNGRADED module_progress signal (PROVE feedback-only,
-- LEARN completion markers) — mirrors that table's "never a false 0" invariant.
CREATE TABLE learning_event (
    id              VARCHAR(36) PRIMARY KEY,
    user_id         VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    avatar_id       VARCHAR(36) REFERENCES avatars(id) ON DELETE CASCADE,
    source          VARCHAR(20) NOT NULL,
    provenance      VARCHAR(24) NOT NULL,
    topic_slug      VARCHAR(200),
    score           DECIMAL(5,2),
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    source_row_id   VARCHAR(36) NOT NULL
);
