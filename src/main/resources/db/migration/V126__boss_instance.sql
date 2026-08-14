-- Phase 1 boss battles (v1): a weak-topic detection materializes into a boss
-- the student fights with quiz questions targeting that topic. Server-authoritative:
-- hp/current-question/defeated state live here; the client only renders what this
-- table says. question_pool_json is the server-only generated question set
-- (INCLUDES the correct answer) for this boss instance — never returned raw to the
-- client; every served question goes back through QuizService.serveGradable (the
-- existing answer-exposure chokepoint) to build the response DTO.
CREATE TABLE boss_instance (
    id                  VARCHAR(36)  PRIMARY KEY,
    user_id             VARCHAR(36)  NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    avatar_id           VARCHAR(36)  NOT NULL REFERENCES avatars(id) ON DELETE CASCADE,
    topic_slug          VARCHAR(200) NOT NULL,
    question_pool_json  TEXT         NOT NULL,
    current_index       INT          NOT NULL DEFAULT 0,
    hp_remaining        INT          NOT NULL,
    hp_max              INT          NOT NULL,
    defeated            BOOLEAN      NOT NULL DEFAULT FALSE,
    reward_unlocked     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    defeated_at         TIMESTAMPTZ
);

-- Fast "does this avatar have an active boss" lookup — the detect-or-get chokepoint.
CREATE INDEX idx_boss_instance_active ON boss_instance (avatar_id) WHERE defeated = FALSE;
