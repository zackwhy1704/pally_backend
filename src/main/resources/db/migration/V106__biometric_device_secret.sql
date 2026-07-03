-- Fix: biometric /verify (permitAll) minted a session JWT from just (userId, deviceId)
-- — both non-secret — with NO proof-of-possession (the challenge/signature scaffolding
-- was never consumed). Add a per-registration high-entropy device SECRET, issued at
-- register time (register is authenticated, so issuing it is safe), stored here as a
-- SHA-256 hash. /verify now requires the raw secret and constant-time-compares its hash.
--
-- Backward compat: existing rows have secret_hash = NULL. verifyBiometric REJECTS a
-- NULL-secret registration (fail closed), so pre-existing biometric devices must
-- re-register once (a one-time re-enable, password login still works meanwhile).
ALTER TABLE biometric_registrations ADD COLUMN secret_hash VARCHAR(64);
