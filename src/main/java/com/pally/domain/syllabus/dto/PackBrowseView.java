package com.pally.domain.syllabus.dto;

/**
 * One "browse starter content" row. Only ever built from PUBLISHED packs that have at
 * least one independently-servable item — see {@code SyllabusContentPackService#browsePublished}.
 */
public record PackBrowseView(
        String packId,
        String syllabusCode,
        String topicTag,
        int moduleCount,
        int servableItemCount
) {
}
