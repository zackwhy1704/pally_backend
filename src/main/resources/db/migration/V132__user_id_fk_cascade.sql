-- ACCOUNT DELETION integrity: close the user_id orphan gap on the three tables
-- that were actually holding orphaned rows.
--
-- WHY THIS EXISTS
-- 140 avatars, and through them 78 chat_messages / 92 knowledge_files / 719
-- flashcards / 152 wiki_pages / 136 learning_module rows, referenced users that
-- no longer exist. A deleted user's uploaded material, generated content, and
-- own chat messages therefore persisted indefinitely — a PDPA erasure gap.
--
-- IT IS NOT A CODE DEFECT. Source review found exactly ONE path that removes a
-- users row (DeleteAccountUseCase:295), it deletes avatars first (loop at
-- 218-224 -> deleteAvatarData -> avatarRepo.deleteById), and the whole purge is
-- one @Transactional unit with NO REQUIRES_NEW anywhere, so a mid-loop failure
-- rolls the user delete back too. A partial purge cannot produce an orphan.
-- The orphans are consistent with deletes issued OUTSIDE the application
-- (direct SQL against test accounts); all 140 date to 2026-05/06 and none to
-- 2026-07 or later, while a clean purge ran as recently as 2026-08-22.
--
-- So this constraint is a BACKSTOP against the out-of-band path that code
-- review cannot reach — which is precisely what a database constraint is for.
--
-- ORDERING (mandatory): this migration CANNOT be applied while violating rows
-- exist. ADD CONSTRAINT validates immediately and would abort the deploy. The
-- 140 orphaned avatars (and their cascade) must be purged BEFORE this ships.
--
-- SCOPE: deliberately these three columns only. session_states.user_id has the
-- same gap (currently 0 orphaned rows) and 27 other user_id columns lack an FK;
-- widening that is a separate, deliberate decision, not a ride-along here.
--
-- avatar_id is NOT touched: wiki_pages, flashcards, learning_module,
-- chat_messages, knowledge_files, chat_sessions, chat_session_summary,
-- hint_trees and session_states ALREADY have avatar_id -> avatars.id
-- ON DELETE CASCADE. That is why deleting the orphaned avatars is sufficient to
-- clear their content, and why this file adds nothing for those paths.

-- Supporting indexes on the referencing side. Postgres does not require these
-- for the constraint itself, but a cascading delete from users would otherwise
-- sequential-scan each child table. avatars.user_id is already indexed
-- (idx_avatars_user_id); the other two are not.
CREATE INDEX IF NOT EXISTS idx_chat_messages_user_id
    ON chat_messages (user_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_files_user_id
    ON knowledge_files (user_id);

-- All three columns are varchar(36) NOT NULL and users.id is the varchar(36)
-- primary key, so the types line up exactly and no NULL semantics apply.
ALTER TABLE avatars
    ADD CONSTRAINT avatars_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE;

ALTER TABLE chat_messages
    ADD CONSTRAINT chat_messages_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE;

ALTER TABLE knowledge_files
    ADD CONSTRAINT knowledge_files_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE;
