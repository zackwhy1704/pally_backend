package com.pally.domain.centre;

import java.time.Instant;
import java.util.Optional;

/**
 * Domain port for persisting AI class reports. The JPA adapter lives in
 * {@code infrastructure/persistence/centre}. Replaces the old in-process cache
 * so reports survive redeploys and are shared across instances.
 */
public interface ClassReportStore {

    /** The persisted report row for a class, if any. */
    Optional<StoredReport> find(String classId);

    /** Upsert the row to {@code generating} and stamp {@code updatedAt=now}. */
    void markGenerating(String classId);

    /** Upsert the row to {@code ready} with the narrative and generation time. */
    void markReady(String classId, String narrative, Instant generatedAt);

    /** Upsert the row to {@code failed} with the error message. */
    void markFailed(String classId, String error);

    /** Delete the row so the next read regenerates (used by evict / force-refresh). */
    void delete(String classId);

    String STATUS_GENERATING = "generating";
    String STATUS_READY = "ready";
    String STATUS_FAILED = "failed";

    record StoredReport(String classId, String narrative, String status,
                        String error, Instant generatedAt, Instant updatedAt) {}
}
