package com.pally.api.knowledge;

import com.pally.domain.knowledge.WikiPage;
import com.pally.infrastructure.persistence.review.ContentReviewRequestJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PART 1.5 — server-derived reviewState for all four confidence states.
 */
@ExtendWith(MockitoExtension.class)
class WikiPageResponseMapperTest {

    @Mock ContentReviewRequestJpaRepository reviewRepo;

    WikiPageResponseMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new WikiPageResponseMapper(reviewRepo);
        ReflectionTestUtils.setField(mapper, "lowConfidenceThreshold", 60);
    }

    private WikiPage pageWith(WikiPage.Certainty certainty, int quality) {
        WikiPage p = WikiPage.reconstitute(
                "id-1", "av-1", "slug", "Title", "content",
                certainty, Instant.now(), quality, null, null,
                certainty == WikiPage.Certainty.VERIFIED);
        return p;
    }

    @Test
    void uncertain_isFlagged() {
        assertThat(mapper.deriveReviewState(pageWith(WikiPage.Certainty.UNCERTAIN, 90)))
                .isEqualTo("FLAGGED");
    }

    @Test
    void verified_isVerified() {
        assertThat(mapper.deriveReviewState(pageWith(WikiPage.Certainty.VERIFIED, 50)))
                .isEqualTo("VERIFIED");
    }

    @Test
    void inferredBelowThreshold_isLowConfidence() {
        assertThat(mapper.deriveReviewState(pageWith(WikiPage.Certainty.INFERRED, 59)))
                .isEqualTo("LOW_CONFIDENCE");
    }

    @Test
    void inferredAtOrAboveThreshold_isUnverified() {
        assertThat(mapper.deriveReviewState(pageWith(WikiPage.Certainty.INFERRED, 60)))
                .isEqualTo("UNVERIFIED");
        assertThat(mapper.deriveReviewState(pageWith(WikiPage.Certainty.INFERRED, 100)))
                .isEqualTo("UNVERIFIED");
    }

    // ── DTO additions for the mobile brain-map graph wave ────────────────────

    @Test
    void toResponse_exposesPrerequisitesCertaintyScoreQuizCountAndConflictNote() {
        WikiPage page = WikiPage.reconstitute(
                "id-2", "av-1", "fractions", "Fractions", "body",
                WikiPage.Certainty.INFERRED, Instant.now(),
                70, null, null, false,
                null, 4, 0.8,
                WikiPage.Status.ACTIVE, false, "numbers, division");
        page.setConflictNote("disputes the decimals page");

        var dto = mapper.toResponse(page);

        assertThat(dto.prerequisiteSlugs())
                .as("comma-separated TEXT split into a trimmed list")
                .containsExactly("numbers", "division");
        assertThat(dto.certaintyScore()).isEqualTo(0.8);
        assertThat(dto.quizUseCount()).isEqualTo(4);
        assertThat(dto.conflictNote()).isEqualTo("disputes the decimals page");
    }

    @Test
    void toResponse_nullPrerequisites_yieldsEmptyList_andNullConflictNote() {
        WikiPage page = WikiPage.create("av-1", "cells", "Cells", "body");
        var dto = mapper.toResponse(page);
        assertThat(dto.prerequisiteSlugs()).isEmpty();
        assertThat(dto.conflictNote()).isNull();
        assertThat(dto.certaintyScore()).isEqualTo(0.5); // create() default
    }
}
