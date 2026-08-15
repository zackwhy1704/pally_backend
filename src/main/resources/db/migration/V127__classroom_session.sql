-- Phase 2: a teacher-created live shared boss battle for a whole class.
-- Reuses the EXACT boss_instance mechanics (question_pool_json/current_index/
-- hp_remaining/hp_max/defeated — same shape, same HP-only-on-hit semantics)
-- scoped to a session instead of a single student.
--
-- Deliberately does NOT model participants/nicknames here or anywhere else:
-- ephemeral identity (a student's typed nickname) lives ONLY in the app's
-- in-memory ClassroomEventBus for the session's lifetime and is never
-- persisted, never queryable, never visible after the session ends. Each
-- participant's own answers still land in quiz_question_results/learning_event
-- under their REAL (private) userId/avatarId via the existing Phase-0/Phase-1
-- write paths — this table carries no student identity at all.
CREATE TABLE classroom_session (
    id                  VARCHAR(36)  PRIMARY KEY,
    class_id            VARCHAR(36)  NOT NULL REFERENCES org_class(id) ON DELETE CASCADE,
    teacher_id          VARCHAR(36)  NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- The class corpus avatar the shared questions were generated from —
    -- stored directly so every subsequent call (start/end/state/attack) can
    -- serve/re-serve questions through QuizService.serveGradable without a
    -- second class lookup.
    avatar_id           VARCHAR(36)  NOT NULL REFERENCES avatars(id) ON DELETE CASCADE,
    join_code           VARCHAR(12)  NOT NULL,
    topic_slug          VARCHAR(200) NOT NULL,
    question_pool_json  TEXT         NOT NULL,
    current_index       INT          NOT NULL DEFAULT 0,
    hp_remaining        INT          NOT NULL,
    hp_max              INT          NOT NULL,
    defeated            BOOLEAN      NOT NULL DEFAULT FALSE,
    status              VARCHAR(16)  NOT NULL DEFAULT 'CREATED', -- CREATED | ACTIVE | ENDED
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    started_at          TIMESTAMPTZ,
    ended_at            TIMESTAMPTZ
);

-- Students resolve a session by join code while it's joinable; codes are
-- reused across ENDED sessions (short-lived, class-scoped) so uniqueness is
-- only enforced among currently-live sessions via the partial index.
CREATE UNIQUE INDEX idx_classroom_session_live_join_code
    ON classroom_session (join_code) WHERE status <> 'ENDED';
CREATE INDEX idx_classroom_session_class ON classroom_session (class_id, created_at);
