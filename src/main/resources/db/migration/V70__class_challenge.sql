-- Part A4: class challenge questions with locked answers and a timed reveal.
-- Before reveal_at the correct answer + distribution are server-withheld; only
-- the question (and options for MCQ) and whether the caller answered are shown.

CREATE TABLE class_challenge (
    id         VARCHAR(36)  PRIMARY KEY,
    class_id   VARCHAR(36)  NOT NULL,
    question   TEXT         NOT NULL,
    options    JSONB,
    answer     TEXT         NOT NULL,
    reveal_at  TIMESTAMPTZ  NOT NULL,
    created_by VARCHAR(36)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    notified   BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_class_challenge_class ON class_challenge (class_id, created_at DESC);
CREATE INDEX idx_class_challenge_reveal ON class_challenge (reveal_at) WHERE notified = FALSE;

CREATE TABLE challenge_answer (
    id           VARCHAR(36)  PRIMARY KEY,
    challenge_id VARCHAR(36)  NOT NULL,
    user_id      VARCHAR(36)  NOT NULL,
    answer       TEXT         NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_challenge_answer_user UNIQUE (challenge_id, user_id)
);

CREATE INDEX idx_challenge_answer_challenge ON challenge_answer (challenge_id);
