-- Content-health reaper (Phase 1b) — reaps legacy blank/invalid module content items.
--
-- QUARANTINED and RETIRED are new TERMINAL values of the existing
-- module_content_item.status VARCHAR(20) column — there is no CHECK constraint to widen,
-- so no type change is needed. Neither is on the servable allow-list
-- (ModuleContentItemRepository.SERVABLE_STATUSES = {LIVE, APPROVED}), so a
-- quarantined/retired item is ALREADY excluded from every student-serving read by the
-- Phase-1a filter — i.e. QUARANTINE alone stops a blank item being served, immediately.
--
-- These two columns track regeneration ATTEMPTS + a backoff timestamp so a repeatedly
-- failing (stuck) item cannot monopolize the oldest-first batch head and starve healthy
-- items behind it — the DeletionPurgeReaper anti-starvation lesson, applied here.
ALTER TABLE module_content_item ADD COLUMN IF NOT EXISTS reap_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE module_content_item ADD COLUMN IF NOT EXISTS reap_last_attempt_at TIMESTAMPTZ;
