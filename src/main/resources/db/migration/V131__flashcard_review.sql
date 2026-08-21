-- V131: per-review flashcard history.
--
-- WHY: flashcards holds only the CURRENT SM-2 state and RateFlashcardUseCase
-- overwrites that row on every rating (jpa.save on the same primary key), so
-- every prior recall outcome is permanently destroyed. Production evidence of
-- the cost: 2,106 cards, MAX(repetitions)=1, and no way to tell whether a card
-- sitting at repetitions=0 was never reviewed or was reviewed and reset by a
-- HARD rating. That distinction is unrecoverable today.
--
-- This table is INSTRUMENTATION ONLY. It deliberately does NOT compute or store
-- a retention rate: no card in production has ever been successfully reviewed
-- twice (the 6-day interval has never been reached), so any retention metric
-- would be structurally zero and would assert a claim the data cannot support.
-- This makes retention MEASURABLE later; it does not measure it now.
--
-- Version note: V129 and V130 are already claimed by the unmerged
-- feature/syllabus-content-library branch. V131 avoids a duplicate-version
-- collision if both branches land (Flyway tolerates gaps, never duplicates).
--
-- Both BEFORE and AFTER SM-2 state are recorded: without the prior state you
-- cannot reconstruct whether a review was a first attempt or a genuine repeat,
-- which is the exact question this table exists to answer.
CREATE TABLE flashcard_review (
    id                    VARCHAR(36)  PRIMARY KEY,
    flashcard_id          VARCHAR(36)  NOT NULL REFERENCES flashcards(id) ON DELETE CASCADE,
    avatar_id             VARCHAR(36)  NOT NULL REFERENCES avatars(id) ON DELETE CASCADE,
    rating                VARCHAR(10)  NOT NULL,  -- CardRating: HARD | OKAY | EASY
    quality               INT          NOT NULL,  -- SM-2 q value (HARD=2, OKAY=4, EASY=5)
    reviewed_at           TIMESTAMPTZ  NOT NULL,

    -- SM-2 state as it stood BEFORE this rating was applied.
    prev_repetitions      INT          NOT NULL,
    prev_ease_factor      REAL         NOT NULL,
    prev_interval_days    INT          NOT NULL,
    prev_next_review_at   TIMESTAMPTZ,             -- null on a card's first ever review

    -- SM-2 state AFTER this rating was applied (what the flashcards row now holds).
    new_repetitions       INT          NOT NULL,
    new_ease_factor       REAL         NOT NULL,
    new_interval_days     INT          NOT NULL,
    new_next_review_at    TIMESTAMPTZ  NOT NULL
);

-- Reconstructing one card's review sequence in order — the primary read pattern.
CREATE INDEX idx_flashcard_review_card ON flashcard_review(flashcard_id, reviewed_at);
-- Per-avatar review activity over time (the diagnostic question: who reviews twice?).
CREATE INDEX idx_flashcard_review_avatar ON flashcard_review(avatar_id, reviewed_at);
