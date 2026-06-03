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
        String humanCorrection,
        // Fix 3: provenance — names of knowledge files that contributed to this page
        List<String> sourceFileNames
) {
    /** Builds a response without provenance (e.g. for single-page GET endpoints). */
    public static WikiPageResponse from(WikiPage page) {
        return new WikiPageResponse(
                page.getId(),
                page.getSlug(),
                page.getTitle(),
                page.getContent(),
                page.getCertainty().name().toLowerCase(),
                page.isHasConflict(),
                page.getUpdatedAt(),
                page.getQualityScore(),
                page.isHumanVerified(),
                page.getHumanCorrection(),
                List.of()
        );
    }

    /** Builds a response with provenance file names attached. */
    public static WikiPageResponse from(WikiPage page, List<String> sourceFileNames) {
        return new WikiPageResponse(
                page.getId(),
                page.getSlug(),
                page.getTitle(),
                page.getContent(),
                page.getCertainty().name().toLowerCase(),
                page.isHasConflict(),
                page.getUpdatedAt(),
                page.getQualityScore(),
                page.isHumanVerified(),
                page.getHumanCorrection(),
                sourceFileNames != null ? sourceFileNames : List.of()
        );
    }

    public record ListResponse(List<WikiPageResponse> pages) {}
}
