-- Data-only backfill, no schema change. QuickOnboardService (the app's sole
-- signup path since Flow B was deleted) never flipped users.setup_complete,
-- which register() defaults to false (a leftover from the old two-step wizard
-- where a separate completeSetup() call was the second step). Every account
-- created via quick onboard has been permanently stuck at false server-side —
-- masked for exactly one session by the CLIENT locally forcing
-- setupComplete=true right after signup, then wrongly bounced back into
-- /onboarding/direct on every login after that (showing "already signed in,
-- log out?" for an account that's actually fully set up).
--
-- Scoped to "has at least one avatar" — NOT a blanket flip. A social-sign-in
-- user can also have setup_complete=false, but for them it's a REAL in-progress
-- state (AuthService.signInWithSocial's new-account branch: account_status
-- PENDING_PROFILE, no avatar yet, genuinely hasn't finished the DOB/profile
-- step). Only a row that already has an avatar proves it went through quick
-- onboard's avatar-creation step and was always supposed to be true; a
-- pending-profile social account with zero avatars is left exactly as-is.
UPDATE users
SET setup_complete = TRUE
WHERE setup_complete = FALSE
  AND EXISTS (SELECT 1 FROM avatars a WHERE a.user_id = users.id);
