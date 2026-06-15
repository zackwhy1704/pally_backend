package com.pally.domain.centre;

import java.util.List;
import java.util.Map;

/**
 * Domain port for content review and publishing lifecycle operations.
 * Implemented by an infrastructure adapter; never import JPA here.
 */
public interface ContentReviewPort {

    /**
     * Returns all content items with status {@code DRAFT}.
     * Each map contains: id, moduleId, stage, type, contentJson, answerJson, sortOrder, status.
     */
    List<Map<String, Object>> findAllDraftItems();

    /**
     * Finds a single content item by id. Returns null if not found.
     */
    ContentItemView findById(String itemId);

    /**
     * Updates status (and optionally contentJson/answerJson) on a content item.
     * If the new status is REJECTED, the item is deleted and null is returned.
     *
     * @param command the mutation to apply
     * @return the updated view, or null when the item was deleted (REJECTED)
     */
    ContentItemView updateItem(UpdateItemCommand command);

    /**
     * Sets all DRAFT items to LIVE (bulk approve).
     *
     * @return the number of items approved
     */
    int approveAllDrafts();

    // ── Value objects ────────────────────────────────────────────────────────

    record ContentItemView(
            String id,
            String moduleId,
            String stage,
            String type,
            String contentJson,
            String answerJson,
            int sortOrder,
            String status) {}

    record UpdateItemCommand(
            String itemId,
            String newStatus,
            String contentJson,
            String answerJson) {}
}
