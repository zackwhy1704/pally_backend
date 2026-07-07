-- Per-user AI cost tracking. One row per AI call (Gemini compile chunk, Claude
-- micro-call, etc.) so cost is attributable to individuals and mapped by feature.
-- est_cost_micros = tokens x per-model rates (millionths of a dollar) — an
-- ESTIMATE for RELATIVE "who is expensive" attribution, NOT a reconciliation of
-- the provider invoice (which includes caching, retries, etc.). user_id is
-- nullable: some low-level calls (e.g. Claude micro-calls) lack a user in scope;
-- record null rather than dropping the cost.
CREATE TABLE ai_usage (
    id              VARCHAR(36) PRIMARY KEY,
    user_id         VARCHAR(36),
    call_type       VARCHAR(32) NOT NULL,   -- COMPILE | CHAT | RELEVANCE | MARKING | WEAKNESS_REBUILD | OTHER
    model           VARCHAR(64) NOT NULL,
    input_tokens    BIGINT      NOT NULL,
    output_tokens   BIGINT      NOT NULL,
    est_cost_micros BIGINT      NOT NULL,   -- millionths of USD
    created_at      TIMESTAMP   NOT NULL
);

-- Per-user rollup over a date range (the admin cost endpoint).
CREATE INDEX idx_ai_usage_user ON ai_usage (user_id, created_at);
-- Per-feature rollup (which call types cost the most).
CREATE INDEX idx_ai_usage_type ON ai_usage (call_type, created_at);
