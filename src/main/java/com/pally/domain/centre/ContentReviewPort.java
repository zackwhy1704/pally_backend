package com.pally.domain.centre;

import java.util.List;
import java.util.Map;

/**
 * Domain port for content review and publishing lifecycle operations.
 * Implemented by an infrastructure adapter; never import JPA here.
 */
public interface ContentReviewPort {

    /**
     * Returns all DRAFT content items that belong to the given class (scoped via
     * each item's module.classId), so one centre can never see another's drafts.
     * Each map contains: id, moduleId, stage, type, contentJson, answerJson, sortOrder, status.
     */
    List<Map<String, Object>> findDraftItemsByClass(String classId);

    /**
     * Finds a single content item by id. Returns null if not found.
     */
    ContentItemView findById(String itemId);

    /**
     * Updates status (and optionally contentJson/answerJson) on a content item.
     * The item MUST belong to {@code command.classId()} (verified via its module);
     * a mismatch throws so a caller can't edit another centre's content by id.
     * If the new status is REJECTED, the item is deleted and null is returned.
     *
     * @param command the mutation to apply
     * @return the updated view, or null when the item was deleted (REJECTED)
     */
    ContentItemView updateItem(UpdateItemCommand command);

    /**
     * Sets the given DRAFT items to LIVE (bulk approve of a pre-validated subset).
     * Each id MUST belong to {@code classId} (verified via its module) or it is skipped,
     * so one centre can never approve another's content. The domain service pre-filters
     * to the items that PASS validation, so a blank/invalid draft is never flipped live.
     *
     * @return the number of items actually approved
     */
    int approveItems(String classId, List<String> itemIds);

    // ── Value objects ────────────────────────────────────────────────────────

    record ContentItemView(
            String id,
            String moduleId,
            String stage,
            String type,
            String contentJson,
            String answerJson,
            int sortOrder,
            String status,
            String verificationJson) {}

    record UpdateItemCommand(
            String classId,
            String itemId,
            String newStatus,
            String contentJson,
            String answerJson) {}
}
