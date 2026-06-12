-- V66 — Birth YEAR for age-conditional consent (PDPC 2024).
--
-- Data minimisation: we store the birth YEAR only, never a full DOB or NRIC.
-- The server derives `isUnder13` from this year using Singapore wall-clock time.
-- Existing rows get NULL → treated as 13+ (they self-signed before this gate),
-- which is the conservative, non-blocking default for already-active accounts.
ALTER TABLE users ADD COLUMN birth_year INT;
