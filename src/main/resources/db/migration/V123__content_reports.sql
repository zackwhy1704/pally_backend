-- Child-safety: user-submitted reports of inappropriate/wrong AI (Mochi) chat responses.
-- Live Mochi chat is UNREVIEWED AI output (unlike wiki/module content, which a teacher
-- approves at the review step). This is the human-review report path (child safety + Apple's
-- 2026 AI-content requirement). Distinct from the chat feedback endpoint/enum — do not conflate.
--
-- Deliberately SELF-CONTAINED: message_text carries the reported content VERBATIM, NOT a FK to
-- chat_messages — the rendered assistant message id is a CLIENT temp id that may not be persisted
-- when Report is tapped (exactly when a child would tap it), and an incident record must survive
-- deletion of the avatar/message. No FK to avatars/users for the same reason: a report is an audit
-- record that must outlive a deleted avatar. Additive only — no changes to existing tables.
CREATE TABLE content_reports (
    id                VARCHAR(36)  PRIMARY KEY,
    avatar_id         VARCHAR(36)  NOT NULL,
    user_id           VARCHAR(36)  NOT NULL,
    reason            VARCHAR(32)  NOT NULL,   -- UNSAFE | WRONG_OR_MISLEADING | OTHER
    comment           TEXT,                    -- optional free text from the reporter
    message_text      TEXT         NOT NULL,   -- the reported Mochi content, verbatim
    client_message_id VARCHAR(64),             -- best-effort; the client temp id, nullable
    created_at        TIMESTAMP    NOT NULL
);

-- Newest-first triage listing (the future admin surface, when volume warrants it).
CREATE INDEX idx_content_reports_created_at ON content_reports (created_at);
CREATE INDEX idx_content_reports_avatar ON content_reports (avatar_id, created_at);
