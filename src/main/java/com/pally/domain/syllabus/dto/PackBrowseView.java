package com.pally.domain.syllabus.dto;

/**
 * One "browse starter content" row. Only ever built from PUBLISHED packs that have at
 * least one independently-servable item — see {@code SyllabusContentPackService#browsePublished}.
 *
 * <p>Deliberately does NOT expose the pack's internal syllabusCode/topicTag (e.g.
 * "SG-G3-COMPUTING-7155") — those are backend-only per the brand-positioning decision.
 * {@code displayLabel} is the only client-safe text (V130).
 */
public record PackBrowseView(
        String packId,
        String displayLabel,
        int moduleCount,
        int servableItemCount
) {
}
