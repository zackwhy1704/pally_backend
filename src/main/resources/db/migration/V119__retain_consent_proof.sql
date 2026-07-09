-- ACCOUNT DELETION Phase 1 — RETAIN consent proof through erasure (LOCKED survivor
-- policy + explicit DPO decision).
--
-- consent_records + consent_requests are the PDPA / PDPC EVIDENCE that a valid
-- (parental) consent was obtained. Their purpose ACTIVATES at and after erasure — the
-- scenario they exist for is a regulator or parent asking, years later, "you processed
-- my 11-year-old's data in 2026 — on what basis?", answerable only by a record naming
-- WHO consented, WHEN, and by which MECHANISM. Anonymized consent proof cannot answer
-- that question, so the identity link IS the evidence and must survive.
--
-- Both tables were NOT NULL REFERENCES users(id) ON DELETE CASCADE, which destroyed
-- exactly this proof the moment the user row was deleted (and DeleteAccountUseCase also
-- deleted them explicitly). Drop the CASCADE FK on both so the rows survive the user
-- purge with their identifier intact; the columns remain plain VARCHARs (no FK).
--
-- Minimization discipline (what makes retain honest, not a loophole): the purge
-- additionally SCRUBS the reusable approval token in consent_requests (see
-- DeleteAccountUseCase) — we retain the proof core, not a live record. consent_records
-- is already minimal (consenter/method/purposes/policy_version/timestamps) so it is
-- retained verbatim. The retained rows are EVIDENCE-ONLY: no operational read path may
-- query consent by a purged user id.
--
-- Retention DURATION (how many years post-deletion) is a DPIA open item for the DPO —
-- indefinite retention is its own PDPA question. The code ships with the retain; the
-- expiry is the lawyer's number.
DO $$
DECLARE fk text;
BEGIN
    SELECT conname INTO fk FROM pg_constraint
        WHERE conrelid = 'consent_records'::regclass AND contype = 'f'
          AND confrelid = 'users'::regclass;
    IF fk IS NOT NULL THEN
        EXECUTE 'ALTER TABLE consent_records DROP CONSTRAINT ' || quote_ident(fk);
    END IF;

    SELECT conname INTO fk FROM pg_constraint
        WHERE conrelid = 'consent_requests'::regclass AND contype = 'f'
          AND confrelid = 'users'::regclass;
    IF fk IS NOT NULL THEN
        EXECUTE 'ALTER TABLE consent_requests DROP CONSTRAINT ' || quote_ident(fk);
    END IF;
END $$;
