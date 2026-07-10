package com.pally.domain.module;

import java.util.List;
import java.util.Optional;

/**
 * Domain port for {@link ModuleContentItem} persistence.
 * The JPA adapter lives in {@code infrastructure/persistence/module}.
 */
public interface ModuleContentItemRepository {

    /**
     * FAIL-CLOSED allow-list of statuses whose items are SERVED to students (the
     * ConsentGuard.ACTIVE_STATUSES pattern): an item reaches a device only if its
     * status is on THIS list. Everything else — DRAFT (unreviewed / mid-regenerate),
     * ARCHIVED (withdrawn), REJECTED, and the reaper's QUARANTINED / RETIRED — is NOT
     * served, and any future/unknown status defaults to not-served (never leaks).
     *
     * <p>Use the {@code findServable*} methods for anything that returns item CONTENT to
     * a student. GATING/EXISTENCE counts (the PROVE-generation gate), the
     * teacher-reviewed BADGE, and completion DENOMINATORS deliberately read the FULL set
     * via {@code findByModuleId*} — filtering those would trigger spurious regeneration,
     * neuter the badge, and skew mastery. Keep the two families distinct.
     */
    List<String> SERVABLE_STATUSES = List.of("LIVE", "APPROVED");

    /// Content-health-reaper TERMINAL statuses (NOT on SERVABLE_STATUSES → already excluded
    /// from every serving read by Phase 1a). A blank/invalid item is QUARANTINED (stops
    /// being served immediately), then either regenerated back to LIVE or RETIRED — RETIRED
    /// keeps the row (module_progress.item_id FK; NEVER deleted).
    String STATUS_QUARANTINED = "QUARANTINED";
    String STATUS_RETIRED = "RETIRED";

    /**
     * REAPER SCAN cursor batch (Phase 1b): up to {@code limit} SERVABLE items not scanned
     * since {@code retryCutoff} (reap_last_attempt_at null or older), oldest-scan first —
     * so successive runs progress across the whole corpus instead of re-scanning the same
     * head (the anti-starvation cursor). The reaper reads the FULL-STATUS family, NEVER
     * findServable*: it exists to act ON items by status, so a servable-only helper would
     * hide its own candidates. (Only servable items are scanned — only they reach students.)
     */
    List<ModuleContentItem> findReapScanCandidates(java.time.Instant retryCutoff, int limit);

    /** Read-only paging over servable items for the DRY_RUN damage report (page 0-indexed). */
    List<ModuleContentItem> findServablePage(int pageNumber, int pageSize);

    ModuleContentItem save(ModuleContentItem item);

    List<ModuleContentItem> saveAll(List<ModuleContentItem> items);

    Optional<ModuleContentItem> findById(String id);

    List<ModuleContentItem> findByModuleIdOrderBySortOrder(String moduleId);

    List<ModuleContentItem> findByModuleIdAndStageOrderBySortOrder(String moduleId, String stage);

    /** SERVABLE items only (SERVABLE_STATUSES) — for returning content to a student. */
    List<ModuleContentItem> findServableByModuleIdOrderBySortOrder(String moduleId);

    /** SERVABLE items for one stage only — for returning content to a student. */
    List<ModuleContentItem> findServableByModuleIdAndStageOrderBySortOrder(String moduleId, String stage);

    int countByModuleIdAndStage(String moduleId, String stage);

    /**
     * Deletes all content items belonging to the given module.
     * Used by the centre regenerate flow to clear stale draft items before
     * re-generating with teacher guidance.
     */
    void deleteByModuleId(String moduleId);
}
