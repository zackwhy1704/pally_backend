-- APP STORE GUIDELINE 1.2 — user blocking on user-generated content.
--
-- WHY THIS EXISTS
-- Study Groups show content authored by other students: shared notes (attributed
-- by name and tappable through to the full note), member display names, and
-- group names. Guideline 1.2 requires BOTH a way to report objectionable content
-- and a way to BLOCK the user who posted it. Reporting already existed
-- (group_reports, V30). Blocking did not exist anywhere.
--
-- WHY OWNER-MODERATION WAS NOT ENOUGH
-- V30's comment proposed that an owner reviews reports and kicks the member.
-- That is moderation, not blocking: it makes the REPORTER wait on someone else,
-- and it cannot help at all in a CLASS group, where a student cannot leave
-- (StudyGroupService rejects leave with 403 — "ask your centre to unenrol you").
-- A student in a class therefore had no way whatsoever to stop seeing a
-- classmate's content. That is the case this table exists for.
--
-- SEMANTICS
--   * ONE-DIRECTIONAL. A blocking B hides B's content from A. It does NOT hide
--     A from B. Symmetric blocking would let one student silently remove
--     themselves from another's study group, which is a griefing vector.
--   * MEMBERSHIP IS UNTOUCHED. Blocking never kicks, never unenrols, and never
--     alters group_members. A blocked classmate stays in the class; the blocker
--     simply stops seeing their content.
--   * REVERSIBLE. Unblocking restores visibility. A 13-year-old who mis-taps
--     must not permanently lose a classmate's study notes with no recourse.
--
-- ENFORCED SERVER-SIDE. The group-detail response omits blocked users' notes and
-- member entries entirely. Filtering in the widget tree would still ship the
-- content to the device, which defeats the purpose.

CREATE TABLE IF NOT EXISTS blocked_users (
    id               VARCHAR(36)  PRIMARY KEY,
    -- The user who initiated the block (the one who stops seeing content).
    blocker_user_id  VARCHAR(36)  NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- The user whose content is hidden FROM the blocker.
    blocked_user_id  VARCHAR(36)  NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- A block is a set membership, not a log: re-blocking must not create a second
-- row, or unblock would have to delete an unknown number of duplicates.
CREATE UNIQUE INDEX IF NOT EXISTS ux_blocked_users_pair
    ON blocked_users (blocker_user_id, blocked_user_id);

-- The hot path: "who has THIS viewer blocked?", read on every group-detail load.
CREATE INDEX IF NOT EXISTS idx_blocked_users_blocker
    ON blocked_users (blocker_user_id);
