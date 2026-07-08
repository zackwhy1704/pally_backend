-- Extend the ai_usage ledger (V110) so it answers "what's expensive, for whom,
-- from which trigger" — one table, not two versions of cost truth.
--   purpose_label : the FINE call label (e.g. 'teach-eval', 'module-learn',
--                   'quiz-gen'). call_type (V110) stays as the COARSE category.
--                   RECONCILIATION: query cost-by-purpose over ALL time with
--                   COALESCE(purpose_label, call_type) — old rows (purpose_label
--                   NULL) fall back to the coarse call_type; new rows carry both.
--   avatar_id     : per-avatar attribution (null when not in scope).
--   call_trigger  : compile | page_update | screen_open | user_action |
--                   scheduled | other  ("trigger" is a reserved word → prefixed).
--   success       : the parent AI call succeeded (false = errored/fallback).
--   estimated     : TRUE = token counts are char-estimates (provider usage
--                   metadata was absent); FALSE = measured from the API response.
ALTER TABLE ai_usage ADD COLUMN avatar_id     VARCHAR(36);
ALTER TABLE ai_usage ADD COLUMN purpose_label VARCHAR(64);
ALTER TABLE ai_usage ADD COLUMN call_trigger  VARCHAR(24);
ALTER TABLE ai_usage ADD COLUMN success       BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE ai_usage ADD COLUMN estimated     BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_ai_usage_purpose ON ai_usage (purpose_label, created_at);
CREATE INDEX idx_ai_usage_avatar  ON ai_usage (avatar_id, created_at);
