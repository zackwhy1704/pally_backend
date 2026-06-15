package com.pally.api.knowledge;

import com.pally.domain.knowledge.dto.WikiPageResponse;
import com.pally.domain.knowledge.WikiPage;
import com.pally.infrastructure.persistence.review.ContentReviewRequestJpaEntity.Status;
import com.pally.infrastructure.persistence.review.ContentReviewRequestJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * Builds {@link WikiPageResponse}s with the server-derived {@code reviewState}.
 *
 * <p>reviewState collapses certainty + qualityScore into one client-facing
 * confidence label so the mobile/web UI doesn't re-implement the rules:
 * <ul>
 *   <li>FLAGGED        — certainty == UNCERTAIN (an adult flagged it)</li>
 *   <li>VERIFIED       — certainty == VERIFIED (human-corrected or adult-approved)</li>
 *   <li>LOW_CONFIDENCE — INFERRED AND qualityScore &lt; threshold</li>
 *   <li>UNVERIFIED     — INFERRED otherwise</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class WikiPageResponseMapper {

    private final ContentReviewRequestJpaRepository reviewRepo;

    /// 0–100 quality-score floor; INFERRED pages below it surface as LOW_CONFIDENCE.
    @Value("${app.review.low-confidence-threshold:60}")
    private int lowConfidenceThreshold;

    public WikiPageResponse toResponse(WikiPage page) {
        return toResponse(page, List.of());
    }

    public WikiPageResponse toResponse(WikiPage page, List<String> sourceFileNames) {
        String reviewState = deriveReviewState(page);
        String flagNote = "FLAGGED".equals(reviewState) ? latestFlagNote(page.getId()) : null;
        return WikiPageResponse.withReviewState(
                page, sourceFileNames, reviewState, page.getVerifiedBy(), flagNote);
    }

    String deriveReviewState(WikiPage page) {
        return switch (page.getCertainty()) {
            case UNCERTAIN -> "FLAGGED";
            case VERIFIED -> "VERIFIED";
            case INFERRED -> page.getQualityScore() < lowConfidenceThreshold
                    ? "LOW_CONFIDENCE" : "UNVERIFIED";
        };
    }

    private String latestFlagNote(String pageId) {
        return reviewRepo.findByWikiPageIdAndStatus(pageId, Status.FLAGGED.name()).stream()
                .max(Comparator.comparing(r -> r.getReviewedAt() != null
                        ? r.getReviewedAt() : r.getCreatedAt()))
                .map(r -> r.getFlagNote())
                .orElse(null);
    }
}
