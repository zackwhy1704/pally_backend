package com.pally.domain.knowledge.dto;

import com.pally.domain.knowledge.WikiPage;

import java.time.Instant;
import java.util.Arrays;
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
        List<String> sourceFileNames,
        String reviewState,
        String verifiedBy,
        String flagNote,
        List<String> prerequisiteSlugs,
        double certaintyScore,
        int quizUseCount,
        String conflictNote
) {

    static List<String> splitPrerequisites(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }

    public static WikiPageResponse from(WikiPage page) {
        return from(page, List.of());
    }

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
                sourceFileNames != null ? sourceFileNames : List.of(),
                null,
                page.getVerifiedBy(),
                null,
                splitPrerequisites(page.getPrerequisiteSlugs()),
                page.getCertaintyScore(),
                page.getQuizUseCount(),
                page.getConflictNote()
        );
    }

    public static WikiPageResponse withReviewState(WikiPage page, List<String> sourceFileNames,
                                                   String reviewState, String verifiedBy,
                                                   String flagNote) {
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
                sourceFileNames != null ? sourceFileNames : List.of(),
                reviewState,
                verifiedBy,
                flagNote,
                splitPrerequisites(page.getPrerequisiteSlugs()),
                page.getCertaintyScore(),
                page.getQuizUseCount(),
                page.getConflictNote()
        );
    }

    public record ListResponse(List<WikiPageResponse> pages) {}
}
