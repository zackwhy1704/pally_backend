-- ACCOUNT DELETION / PDPA: keep a failed storage delete DISCOVERABLE.
--
-- THE DEFECT THIS CLOSES
-- DeleteAccountUseCase.deleteAvatarData deleted each knowledge file's stored
-- object and then its DB row — but the row delete ran UNCONDITIONALLY, including
-- when the storage delete threw. The purge then completed and reported success
-- while the user's uploaded file still existed in object storage, and the only
-- record of its storage key had just been deleted. The surviving file was
-- therefore permanently unfindable: unreachable by the app, invisible to audit,
-- and belonging to a user who had been told they were erased.
--
-- That is worse in kind than the orphaned-avatar gap V132 closed. Those rows
-- were at least discoverable and could be purged. This artefact could not.
--
-- WHY NOT JUST FAIL THE PURGE
-- Letting the exception propagate would abort the whole @Transactional purge and
-- leave the account DELETION_PENDING for the reaper. Rejected: one permanently
-- dead key (already gone from R2, malformed, bucket ACL change) would block that
-- account's erasure forever, leaving all of its DATABASE rows in place too. That
-- converts a leaked-file problem into a never-erased-account problem — strictly
-- worse under PDPA. The user's erasure must complete on time; the stray object
-- becomes tracked work instead of silence.
--
-- user_id IS DELIBERATELY EXCLUDED FROM THIS TABLE.
-- The storage key alone is sufficient to complete the deletion, so retaining a
-- user identifier here would be personal data kept without a purpose — the very
-- thing this table exists to fix. It would make the erasure record itself a small
-- ongoing exposure. avatar_id is kept: by the time a row is written the avatar is
-- already deleted, so the id carries no live identity and is useful for tracing.

CREATE TABLE IF NOT EXISTS orphaned_storage_object (
    id              VARCHAR(36)  PRIMARY KEY,
    -- Storage keys are not bounded by our schema, so TEXT rather than a guess.
    storage_key     TEXT         NOT NULL,
    -- Nullable: a failure can be recorded for a key with no surviving avatar
    -- context. Not an FK — avatars is deleted by the time this row is written.
    avatar_id       VARCHAR(36),
    failed_at       TIMESTAMPTZ  NOT NULL,
    last_attempt_at TIMESTAMPTZ,
    attempts        INTEGER      NOT NULL DEFAULT 0,
    last_error      TEXT
);

-- One row per key. The sweeper retries the same key repeatedly; without this a
-- retry storm would insert a duplicate row per attempt and the queue depth would
-- measure retries instead of leaked objects.
CREATE UNIQUE INDEX IF NOT EXISTS ux_orphaned_storage_object_key
    ON orphaned_storage_object (storage_key);

-- The sweeper claims oldest-first.
CREATE INDEX IF NOT EXISTS idx_orphaned_storage_object_failed_at
    ON orphaned_storage_object (failed_at);
