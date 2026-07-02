-- Debounce + wins state for the WEAKNESS_PROFILE head (activation).
-- weak_slugs: the sorted, comma-joined set of the student's currently-weak topic
--   slugs for (user_id, subject). Used as the debounce signature — a recompile
--   only fires when this set MATERIALLY changes (a compile-per-session storm
--   otherwise). recent_wins: topics that just left the weak set (recovered), so
--   the student can be shown "you improved on X" even after the recompile drops
--   the recovered page. Per (user_id, subject); the weakness brain is private.
CREATE TABLE weakness_profile_state (
    id          VARCHAR(36)  PRIMARY KEY,
    user_id     VARCHAR(64)  NOT NULL,
    subject     VARCHAR(32)  NOT NULL,
    weak_slugs  TEXT,
    recent_wins TEXT,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_weakness_state_user_subject UNIQUE (user_id, subject)
);
