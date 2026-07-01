-- Marking-corpus mapping: resolves a centre's compiled MARKING STANDARD brain by
-- (org_id, subject). The marking standard is now compiled through the SAME wiki
-- harness as student notes (incremental compile, merge-into-existing, conflict,
-- decay) instead of the flat MarkingReferenceService concatenation. The brain
-- itself is an avatar with kind=MARKING_CORPUS (hidden from students); this table
-- is the lookup so all of an org's teachers of a subject share and improve ONE
-- standard. One marking brain per (org, subject) — enforced by the unique key.
CREATE TABLE marking_corpus (
    id          VARCHAR(36) PRIMARY KEY,
    org_id      VARCHAR(36) NOT NULL,
    -- Subject enum name (e.g. MATH, SCIENCE) — matches avatars.subject.
    subject     VARCHAR(30) NOT NULL,
    -- The MARKING_CORPUS avatar holding this subject's compiled marking wiki.
    avatar_id   VARCHAR(36) NOT NULL,
    created_at  TIMESTAMP   NOT NULL,
    CONSTRAINT uq_marking_corpus_org_subject UNIQUE (org_id, subject)
);

CREATE INDEX idx_marking_corpus_avatar ON marking_corpus (avatar_id);
