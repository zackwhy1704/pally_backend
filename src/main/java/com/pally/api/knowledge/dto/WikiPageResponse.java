package com.pally.api.knowledge.dto;

import com.pally.domain.knowledge.WikiPage;

import java.time.Instant;
import java.util.List;

public record WikiPageResponse(
        String id,
        String slug,
        String title,
        String content,
        String certainty,
        boolean hasConflict,
        Instant updatedAt,
        int qualityScore,
        boolean humanVerified,
        String humanCorrection
) {
    public static WikiPageResponse from(WikiPage page) {
        return new WikiPageResponse(
                page.getId(),
                page.getSlug(),
                page.getTitle(),
                page.getContent(),
                page.getCertainty().name().toLowerCase(),
                false,
                page.getUpdatedAt(),
                page.getQualityScore(),
                page.isHumanVerified(),
                page.getHumanCorrection()
        );
    }

    public record ListResponse(List<WikiPageResponse> pages) {}
}
