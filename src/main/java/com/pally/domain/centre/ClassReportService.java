package com.pally.domain.centre;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

/**
 * Fast, non-blocking façade for the AI class report. Owns auth + the
 * cache/dispatch decision; the slow Claude call runs asynchronously in
 * {@link ClassReportGenerator} and the result is persisted in {@link ClassReportStore}.
 *
 * <p>The GET never blocks on Claude. It returns one of three states:
 * <pre>
 *   { "status": "ready",      "narrative": "...", "generatedAt": "...", "cached": true|false }
 *   { "status": "generating" }
 *   { "status": "failed",     "message": "..." }
 * </pre>
 * This eliminated the synchronous-LLM-in-a-GET 504 (web client aborts at 30s).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClassReportService {

    private static final int CACHE_TTL_MINUTES = 60;
    /** A 'generating' row older than this is treated as stale (crashed / rejected
     *  dispatch) and re-dispatched, so a report can never get stuck forever. */
    private static final int GENERATING_STALE_MINUTES = 2;

    private final CentreAccessService centreAccessService;
    private final ClassReportStore store;
    private final ClassReportGenerator generator;

    public Map<String, Object> getReport(String userId, String orgId, String classId) {
        return getReport(userId, orgId, classId, false);
    }

    /**
     * Returns the report state for a class, dispatching async generation on a
     * miss/stale/refresh. Auth: staff or owner of the org (checked first).
     *
     * @param forceRefresh when true, evicts any existing row and regenerates —
     *                     used by the web "Try again" affordance after a failure.
     */
    public Map<String, Object> getReport(String userId, String orgId, String classId,
                                         boolean forceRefresh) {
        centreAccessService.ensureStaff(userId, orgId);

        if (forceRefresh) {
            store.delete(classId);
        }

        Instant now = Instant.now();
        ClassReportStore.StoredReport row = forceRefresh ? null : store.find(classId).orElse(null);

        if (row != null) {
            boolean readyAndFresh = ClassReportStore.STATUS_READY.equals(row.status())
                    && row.generatedAt() != null
                    && row.generatedAt().isAfter(now.minus(Duration.ofMinutes(CACHE_TTL_MINUTES)));
            if (readyAndFresh) {
                return ready(row.narrative(), row.generatedAt(), true);
            }

            boolean generatingAndFresh = ClassReportStore.STATUS_GENERATING.equals(row.status())
                    && row.updatedAt() != null
                    && row.updatedAt().isAfter(now.minus(Duration.ofMinutes(GENERATING_STALE_MINUTES)));
            if (generatingAndFresh) {
                // A fresh generation is already in flight — never re-dispatch.
                return generating();
            }

            if (ClassReportStore.STATUS_FAILED.equals(row.status())) {
                // Terminal: surface the failure so the UI can offer "Try again"
                // (which calls back with forceRefresh=true). We do NOT silently
                // auto-retry, which would loop and hammer the Claude budget.
                return failed(row.error());
            }
            // else: stale ready / stale (stuck) generating → fall through and regenerate.
        }

        // No row / stale / failed-refresh → dispatch a fresh async generation.
        store.markGenerating(classId);
        try {
            generator.generate(userId, orgId, classId);
        } catch (RejectedExecutionException rejected) {
            // AI pool saturated. The row stays 'generating'; once it ages past
            // GENERATING_STALE_MINUTES the next poll re-dispatches. Never 500.
            log.warn("[ClassReport] AI pool rejected generation classId={} — client will re-poll: {}",
                    classId, rejected.getMessage());
        }
        return generating();
    }

    /**
     * Evicts the persisted report for a class, forcing a fresh generation on the
     * next call. Backed by the store (a redeploy no longer loses reports).
     */
    public void evictCache(String classId) {
        store.delete(classId);
    }

    // ── Response shapes ──────────────────────────────────────────────────────

    private Map<String, Object> ready(String narrative, Instant generatedAt, boolean cached) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", ClassReportStore.STATUS_READY);
        m.put("narrative", narrative);
        m.put("generatedAt", generatedAt != null ? generatedAt.toString() : null);
        m.put("cached", cached);
        return m;
    }

    private Map<String, Object> generating() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", ClassReportStore.STATUS_GENERATING);
        return m;
    }

    private Map<String, Object> failed(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", ClassReportStore.STATUS_FAILED);
        m.put("message", message != null ? message
                : "Report generation failed — please try again.");
        return m;
    }
}
