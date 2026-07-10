-- AUTH HARDENING A follow-up — UNIQUE lower(email) enforcement at the DB layer.
--
-- V114 added a NON-unique lower(email) index, deliberately not unique because a UNIQUE
-- index would FAIL to build if pre-normalization case-variant duplicates existed
-- (e.g. User@x.com + user@x.com). Deduping is a human call, so V114 gated the upgrade on
-- a duplicate count.
--
-- GATE CLEARED (2026-07-10, read-only against prod):
--   SELECT lower(email), count(*) FROM users GROUP BY 1 HAVING count(*) > 1;  -> 0 rows
--   (38 total users). With zero case-variant duplicates, the UNIQUE build is safe.
--
-- This makes the DB enforce what EmailNormalizer already assumes at the app layer
-- (trim+lowercase on every auth lookup/store) — defense in depth against any future code
-- path that reaches persistence without normalizing. The raw-column UNIQUE (V3) remains
-- and is case-SENSITIVE; this functional index adds the case-INSENSITIVE guarantee.
--
-- ⚠ MIGRATION ORDER: this is V121 because V120 is claimed by the held
-- `feat/content-health-reaper` branch. Flyway out-of-order is DISABLED, so
-- `feat/content-health-reaper` (V120) MUST merge before this (V121) — or renumber one —
-- to avoid an out-of-order rejection on deploy.
--
-- 38 rows → a plain (non-CONCURRENT) unique index build inside Flyway's transaction is fine.

DROP INDEX IF EXISTS idx_users_email_lower;
CREATE UNIQUE INDEX IF NOT EXISTS idx_users_email_lower ON users (lower(email));
