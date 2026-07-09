-- AUTH HARDENING A — reconcile legacy web centre-admin accounts.
--
-- The web (memoly) signup is adults-only and never sent a birth year, so those accounts
-- have birth_year IS NULL. Now that ADULT is an age-EXEMPT account type, mark them so the
-- age inversion (isUnder13(null)=true) doesn't wrongly gate adult web admins.
--
-- Safe target: a mobile STUDENT always carries a synthetic birth year (the onboarding
-- flow derives one), so birth_year IS NULL on a plain SOLO account can only be a legacy
-- web admin (confirmed: the prod null-birth-year count was small and entirely web signups).
-- CHILD / PARENT are left untouched.
UPDATE users
   SET account_type = 'ADULT'
 WHERE birth_year IS NULL
   AND account_type = 'SOLO';
