-- Forward-compatibility hedge only: records who OWNS a marking standard so a
-- future phase can offer per-teacher (or other) marking brains without a
-- migration scramble. Every corpus today is ORG-scoped (one per org+subject) and
-- nothing reads this column yet — it is dormant until a real centre signals which
-- ownership model wins. The existing UNIQUE(org_id, subject) is unchanged; no
-- teacher_id and no key change here.
ALTER TABLE marking_corpus ADD COLUMN scope VARCHAR(16) NOT NULL DEFAULT 'ORG';
