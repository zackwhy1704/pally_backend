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
        List<String> sourceFileNames,
        // Trusted-adult review: server-derived confidence label
        // (FLAGGED / VERIFIED / LOW_CONFIDENCE / UNVERIFIED).
        String reviewState,
        // Reviewer/verifier display name (null until verified).
        String verifiedBy,
        // Reviewer's note — only populated when reviewState == FLAGGED.
        String flagNote
) {
    /** Builds a response without provenance (e.g. for single-page GET endpoints). */
    public static WikiPageResponse from(WikiPage page) {
        return from(page, List.of());
    }

    /** Builds a response with provenance file names attached (no reviewState). */
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
                null
        );
    }

    /** Full builder with the server-derived reviewState + flagNote. */
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
                flagNote
        );
    }

    public record ListResponse(List<WikiPageResponse> pages) {}
}
