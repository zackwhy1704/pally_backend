-- Persisted AI class-report cache. Replaces the in-process ConcurrentHashMap in
-- ClassReportService, which was wiped on every Railway redeploy and not shared
-- across instances — making cache misses (and thus the 30s 504s) frequent.
-- Generation is now async (status='generating') so the GET returns fast.
CREATE TABLE class_reports (
    class_id      VARCHAR(64) PRIMARY KEY,
    narrative     TEXT,
    status        VARCHAR(16) NOT NULL,   -- 'generating' | 'ready' | 'failed'
    error         TEXT,
    generated_at  TIMESTAMPTZ,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
