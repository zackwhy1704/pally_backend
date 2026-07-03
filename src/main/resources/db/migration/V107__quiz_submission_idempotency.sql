-- Quiz submit idempotency (bug-hunt #5 server half). A quiz submission carries no
-- quizId/date/attemptId, and quizzes are per-avatar + ad-hoc (a user legitimately
-- submits several a day, one per subject avatar), so a (user_id, quiz_date) unique
-- would WRONGLY reject a second, different-avatar quiz. The only thing that makes
-- two POSTs "the same submission" is that they're a retry of one client action —
-- a per-attempt idempotency key. First writer claims the key + grades + stores the
-- result; a retry/race with the same key returns the STORED result instead of
-- re-grading (which would double-credit XP/stars and pollute teacher analytics).
CREATE TABLE quiz_submission_idempotency (
    id              VARCHAR(36)  PRIMARY KEY,
    user_id         VARCHAR(36)  NOT NULL,
    idempotency_key VARCHAR(64)  NOT NULL,
    result_json     TEXT,                       -- NULL between claim and completion
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_quiz_idem_user_key UNIQUE (user_id, idempotency_key)
);
